//! The HTTP side: a forward proxy on 127.0.0.1 that answers from the game server.

use std::convert::Infallible;
use std::io::{self, Read};
use std::pin::Pin;
use std::sync::{Arc, OnceLock};
use std::task::{Context, Poll};

use bytes::Bytes;
use http_body_util::{combinators::BoxBody, BodyExt, Empty, Full};
use hyper::body::{Frame, Incoming, SizeHint};
use hyper::header::{self, HeaderValue};
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::client::legacy::{connect::HttpConnector, Client};
use hyper_util::rt::{TokioExecutor, TokioIo};
use percent_encoding::{utf8_percent_encode, AsciiSet, CONTROLS};
use tokio::sync::{mpsc, oneshot};

use crate::{content_type, GameServer, Key, Resolved};

type Body = BoxBody<Bytes, io::Error>;

/// Files up to this size are read in one go; bigger ones stream in CHUNK pieces.
const SMALL: u64 = 512 * 1024;
const CHUNK: usize = 256 * 1024;

pub struct ProxyHandle {
    pub port: u16,
    pub server: Arc<GameServer>,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl ProxyHandle {
    pub fn stop(mut self) {
        self.shutdown_now();
    }

    fn shutdown_now(&mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

impl Drop for ProxyHandle {
    fn drop(&mut self) {
        self.shutdown_now();
    }
}

/// Start serving on 127.0.0.1:`port` (0 picks a free port) on a private runtime thread.
pub fn start(server: Arc<GameServer>, port: u16) -> io::Result<ProxyHandle> {
    let listener = std::net::TcpListener::bind(("127.0.0.1", port))?;
    listener.set_nonblocking(true)?;
    let port = listener.local_addr()?.port();
    let (tx, mut rx) = oneshot::channel::<()>();
    let srv = server.clone();
    let thread = std::thread::Builder::new().name("fp-proxy".into()).spawn(move || {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("fp-proxy-io")
            .enable_all()
            .build()
            .expect("tokio runtime");
        rt.block_on(async move {
            let listener = tokio::net::TcpListener::from_std(listener).expect("listener");
            log::info!("game server listening on 127.0.0.1:{port}");
            loop {
                tokio::select! {
                    _ = &mut rx => break,
                    acc = listener.accept() => {
                        let Ok((stream, _)) = acc else { continue };
                        let _ = stream.set_nodelay(true);
                        let srv = srv.clone();
                        tokio::spawn(async move {
                            let svc = hyper::service::service_fn(move |req| {
                                let srv = srv.clone();
                                async move { Ok::<_, Infallible>(handle(srv, port, req, false).await) }
                            });
                            let _ = hyper::server::conn::http1::Builder::new()
                                .keep_alive(true)
                                .serve_connection(TokioIo::new(stream), svc)
                                .with_upgrades()
                                .await;
                        });
                    }
                }
            }
        });
        rt.shutdown_background();
    })?;
    Ok(ProxyHandle { port, server, shutdown: Some(tx), thread: Some(thread) })
}

fn is_self(host: &str, port: u16) -> bool {
    let (h, p) = match host.rsplit_once(':') {
        Some((h, p)) => (h, p.parse::<u16>().ok()),
        None => (host, None),
    };
    matches!(h, "127.0.0.1" | "localhost" | "[::1]") && p.is_none_or(|p| p == port)
}

/// `handle` behind a box: the HTTPS path serves requests with `handle` from inside `handle`.
fn handle_boxed(srv: Arc<GameServer>, port: u16, req: Request<Incoming>, https: bool) -> Pin<Box<dyn std::future::Future<Output = Response<Body>> + Send>> {
    Box::pin(handle(srv, port, req, https))
}

/// Every answer may be read by other sites' pages (as on the game server): archived sites call
/// each other (Happy Wheels HTML5 asks the Flash release's level server).
async fn handle(srv: Arc<GameServer>, port: u16, req: Request<Incoming>, https: bool) -> Response<Body> {
    let origin = req.headers().get(header::ORIGIN).cloned();
    if req.method() == Method::OPTIONS && req.headers().contains_key(header::ACCESS_CONTROL_REQUEST_METHOD) {
        let mut r = text(StatusCode::NO_CONTENT, "");
        let h = r.headers_mut();
        h.insert(header::ACCESS_CONTROL_ALLOW_METHODS, HeaderValue::from_static("GET, POST, PUT, OPTIONS, HEAD"));
        if let Some(v) = req.headers().get(header::ACCESS_CONTROL_REQUEST_HEADERS) {
            h.insert(header::ACCESS_CONTROL_ALLOW_HEADERS, v.clone());
        }
        h.insert(header::ACCESS_CONTROL_MAX_AGE, HeaderValue::from_static("86400"));
        allow_origin(&mut r, origin);
        return r;
    }
    let (method, uri) = (req.method().clone(), req.uri().clone());
    let mut r = serve(srv, port, req, https).await;
    log::debug!("{method} {uri} -> {}", r.status().as_u16());
    allow_origin(&mut r, origin);
    r
}

fn allow_origin(r: &mut Response<Body>, origin: Option<HeaderValue>) {
    let h = r.headers_mut();
    if h.contains_key(header::ACCESS_CONTROL_ALLOW_ORIGIN) {
        return;
    }
    match origin {
        Some(o) => {
            h.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, o);
            h.insert(header::ACCESS_CONTROL_ALLOW_CREDENTIALS, HeaderValue::from_static("true"));
        }
        None => {
            h.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, HeaderValue::from_static("*"));
        }
    }
}

async fn serve(srv: Arc<GameServer>, port: u16, req: Request<Incoming>, https: bool) -> Response<Body> {
    if req.method() == Method::CONNECT {
        // HTTPS: accept the tunnel, end TLS here, serve what's inside like http://.
        let host = req.uri().authority().map(|a| a.host().to_string()).unwrap_or_default();
        let cfg = match crate::tls::config_for(&host) {
            Ok(c) => c,
            Err(_) => return text(StatusCode::BAD_GATEWAY, "fp-proxy: no certificate"),
        };
        let srv = srv.clone();
        tokio::spawn(async move {
            let Ok(upgraded) = hyper::upgrade::on(req).await else { return };
            let acceptor = tokio_rustls::TlsAcceptor::from(cfg);
            let Ok(tls) = acceptor.accept(TokioIo::new(upgraded)).await else { return };
            let svc = hyper::service::service_fn(move |req| {
                let srv = srv.clone();
                async move { Ok::<_, Infallible>(handle_boxed(srv, port, req, true).await) }
            });
            let _ = hyper::server::conn::http1::Builder::new().keep_alive(true).serve_connection(TokioIo::new(tls), svc).await;
        });
        return Response::new(empty());
    }
    let uri = req.uri().clone();
    let host_header = req.headers().get(header::HOST).and_then(|h| h.to_str().ok()).unwrap_or("").to_string();

    // Absolute-form (proxy) requests carry the host in the URI. Origin-form requests to the
    // proxy itself use `/<host>/<path>` ("direct mode", handy for tests and native players).
    let (host, path): (String, String) = match uri.authority() {
        Some(a) if !is_self(a.as_str(), port) => (a.as_str().to_string(), uri.path().to_string()),
        _ if !host_header.is_empty() && !is_self(&host_header, port) => (host_header, uri.path().to_string()),
        _ => {
            let p = uri.path().trim_start_matches('/');
            if p == "fp/status" {
                let body = format!("{{\"mounts\":{}}}", srv.mounted().len());
                return with(StatusCode::OK, "application/json", full(body.into()));
            }
            match p.split_once('/') {
                Some((h, rest)) => (h.to_string(), format!("/{rest}")),
                None => (p.to_string(), "/".to_string()),
            }
        }
    };
    let Some(key) = Key::new(&host, &path) else {
        return text(StatusCode::BAD_REQUEST, "fp-proxy: bad request");
    };
    let head = req.method() == Method::HEAD;
    let query = uri.query();
    // Games' server calls (POSTs to level servers and the like) are few and worth seeing.
    if req.method() != Method::GET && !head {
        log::info!("{} {host}{}{}", req.method(), uri.path(), query.map(|q| format!("?{q}")).unwrap_or_default());
    }

    // A game run from its own live website: the site first, the pack only when it cannot be
    // reached. Its server calls always go there.
    let rel_key = key.htdocs_rel();
    if in_domains(&srv.config().live_first, &rel_key) && !tracker(&rel_key) {
        let rel = utf8_percent_encode(&rel_key, PATH_ESC).to_string();
        let q = query.filter(|q| !q.is_empty()).map(|q| format!("?{q}")).unwrap_or_default();
        if req.method() != Method::GET && !head {
            let method = req.method().clone();
            let headers = req.headers().clone();
            let body = req.into_body().collect().await.map(|b| b.to_bytes()).unwrap_or_default();
            return live_forward(method, &headers, body, &rel, &q)
                .await
                .unwrap_or_else(|| text(StatusCode::BAD_GATEWAY, "fp-proxy: the game's site did not answer"));
        }
        if let Some((bytes, ct)) = fetch(&format!("http://{rel}{q}"), "live-first").await {
            let len = bytes.len() as u64;
            let mut r = ok_file(&key.path, len, if head { empty() } else { full(bytes) });
            if let Some(ct) = ct {
                r.headers_mut().insert(header::CONTENT_TYPE, ct);
            }
            return r;
        }
    }

    // A pack's .htaccess may send the request elsewhere first. A redirect to a copy kept in
    // another pack (Happy Wheels HTML5's level server is in its Flash release's 9 GB pack) only
    // when that pack is here: otherwise the request stays, for the game's own site to answer.
    let mounts = srv.mounts.read().unwrap().clone();
    if let Some((code, to)) = crate::rewrite::redirect(&mounts, &key, query)
        .filter(|(_, to)| in_mounts(&mounts, to) || !live_allowed(&srv.config().live_domains, &key.htdocs_rel()))
    {
        log::info!("rewrite: {host}{} -> {to} ({code})", uri.path());
        let mut r = text(StatusCode::from_u16(code).unwrap_or(StatusCode::FOUND), "");
        if let Ok(v) = HeaderValue::from_str(&to) {
            r.headers_mut().insert(header::LOCATION, v);
        }
        return r;
    }
    let resolved = srv.resolve(&key, query);
    // PHP pages run, as on desktop Flashpoint's Legacy server.
    if crate::php::enabled() {
        if let Some((script, root)) = php_script(&srv, &key, &resolved, query).await {
            return php_page(script, root, &host, uri.path(), query.unwrap_or(""), https, req).await;
        }
    }
    match resolved {
        Resolved::Zip { mount, index, size, name } => {
            let mut ct = content_type(&name);
            let body = if head {
                empty()
            } else if size <= SMALL {
                match tokio::task::spawn_blocking(move || mount.read_all(index, size)).await {
                    Ok(Ok(v)) => {
                        if ct == "application/octet-stream" {
                            if let Some(t) = crate::sniff(&v[..v.len().min(512)]) { ct = t.to_string(); }
                        }
                        full(v.into())
                    }
                    _ => return text(StatusCode::INTERNAL_SERVER_ERROR, "fp-proxy: zip read failed"),
                }
            } else {
                stream_blocking(size, move |send| {
                    let mut a = mount.reader();
                    let mut f = a.by_index(index).map_err(io::Error::other)?;
                    pump(&mut f, send)
                })
            };
            file_response(&ct, size, body)
        }
        // A POST and the like to the game's own site (a level server's API): passed on live, as
        // the game's web version does, unless a pack has it. Never answered from the folder of
        // fetched files: a copy there is of the address, not of its answer (an empty
        // totaljerkface.com/get_level.hw saved there once hid Happy Wheels' levels).
        Resolved::NotFound | Resolved::Disk(_) if req.method() != Method::GET && !head && {
            let rel = key.htdocs_rel();
            live_allowed(&srv.config().live_domains, &rel) && !tracker(&rel)
        } => {
            let method = req.method().clone();
            let headers = req.headers().clone();
            let body = req.into_body().collect().await.map(|b| b.to_bytes()).unwrap_or_default();
            let rel = utf8_percent_encode(&key.htdocs_rel(), PATH_ESC).to_string();
            let q = query.filter(|q| !q.is_empty()).map(|q| format!("?{q}")).unwrap_or_default();
            live_forward(method, &headers, body, &rel, &q)
                .await
                .unwrap_or_else(|| text(StatusCode::BAD_GATEWAY, "fp-proxy: the game's site did not answer"))
        }
        Resolved::Disk(p) => {
            let size = std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0);
            let name = p.to_string_lossy().split('?').next().unwrap_or("").to_string();
            let mut ct = content_type(&name);
            if ct == "application/octet-stream" {
                let mut buf = [0u8; 512];
                let n = std::fs::File::open(&p).and_then(|mut f| f.read(&mut buf)).unwrap_or(0);
                if let Some(t) = crate::sniff(&buf[..n]) { ct = t.to_string(); }
            }
            let body = if head {
                empty()
            } else {
                stream_blocking(size, move |send| pump(&mut std::fs::File::open(&p)?, send))
            };
            file_response(&ct, size, body)
        }
        Resolved::AddSlash => {
            let mut loc = format!("{}/", uri.path());
            if let Some(q) = query {
                loc.push('?');
                loc.push_str(q);
            }
            let mut r = text(StatusCode::MOVED_PERMANENTLY, "");
            r.headers_mut().insert(header::LOCATION, HeaderValue::from_str(&loc).unwrap_or(HeaderValue::from_static("/")));
            r
        }
        Resolved::NotFound => match remote(&srv, &key, query).await {
            Some((bytes, ct)) => {
                let len = bytes.len() as u64;
                let mut r = ok_file(&key.path, len, if head { empty() } else { full(bytes) });
                if let Some(ct) = ct {
                    r.headers_mut().insert(header::CONTENT_TYPE, ct);
                }
                r
            }
            None => text(StatusCode::NOT_FOUND, "fp-proxy: not found"),
        },
    }
}

/// The script to run for this request and its site folder, when the file it resolved to (or the
/// one the online Legacy server has for it) is a PHP script.
async fn php_script(srv: &Arc<GameServer>, key: &Key, resolved: &Resolved, query: Option<&str>) -> Option<(std::path::PathBuf, std::path::PathBuf)> {
    match resolved {
        Resolved::Zip { mount, index, .. } => {
            let name = mount.reader().by_index_raw(*index).ok()?.name().ok()?.replace('\\', "/");
            if !crate::php::is_script(&name) {
                // Maybe a script by the site's .htaccess (`AddHandler application/x-httpd-php .hw`).
                let m = mount.clone();
                let extra = tokio::task::spawn_blocking(move || crate::php::handler_exts(&m)).await.ok()?;
                if !crate::php::is_script_in(&name, &extra) {
                    return None;
                }
            }
            let m = mount.clone();
            let dir = tokio::task::spawn_blocking(move || crate::php::unpacked(&m)).await.ok()?.ok()?;
            let site: std::path::PathBuf = name.split('/').take(2).collect();
            Some((dir.join(&name), dir.join(site)))
        }
        Resolved::Disk(p) => {
            if !crate::php::is_script(&p.to_string_lossy()) {
                return None;
            }
            let root = srv.config().htdocs.map(|h| h.join(&key.host)).unwrap_or_else(|| p.parent().map(|x| x.to_path_buf()).unwrap_or_default());
            Some((p.clone(), root))
        }
        Resolved::NotFound if !key.dir && crate::php::is_script(&key.path) => {
            // Only online: fetch the script (the Legacy server stores it as a file) and run it here.
            let (bytes, _) = remote(srv, key, None).await?;
            let cached = srv.config().htdocs.map(|h| h.join(key.htdocs_rel())).filter(|p| p.is_file());
            let script = match cached {
                Some(p) => p,
                None => {
                    let p = crate::php::work_dir()?.join("remote").join(key.htdocs_rel());
                    std::fs::create_dir_all(p.parent()?).ok()?;
                    std::fs::write(&p, &bytes).ok()?;
                    p
                }
            };
            let _ = query;
            let root = script.ancestors().nth(key.htdocs_rel().matches('/').count()).map(|p| p.to_path_buf()).unwrap_or_default();
            Some((script, root))
        }
        _ => None,
    }
}

/// Run a PHP page for this request and answer with what it printed.
async fn php_page(script: std::path::PathBuf, root: std::path::PathBuf, host: &str, path: &str, query: &str, https: bool, req: Request<Incoming>) -> Response<Body> {
    let method = req.method().as_str().to_string();
    let headers: Vec<(String, String)> = req.headers().iter()
        .filter_map(|(k, v)| v.to_str().ok().map(|v| (k.as_str().to_string(), v.to_string())))
        .collect();
    let body = req.into_body().collect().await.map(|b| b.to_bytes()).unwrap_or_default();
    let r = crate::php::Request { method: &method, host, path, query, https, headers, body };
    match crate::php::run(script.clone(), root, r).await {
        Ok(out) => {
            let mut resp = Response::new(if method == "HEAD" { empty() } else { full(out.body) });
            *resp.status_mut() = StatusCode::from_u16(out.status).unwrap_or(StatusCode::OK);
            let mut typed = false;
            for (k, v) in out.headers {
                if let (Ok(k), Ok(v)) = (header::HeaderName::from_bytes(k.as_bytes()), HeaderValue::from_str(&v)) {
                    typed |= k == header::CONTENT_TYPE;
                    resp.headers_mut().append(k, v);
                }
            }
            if !typed {
                resp.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("text/html"));
            }
            resp.headers_mut().insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, HeaderValue::from_static("*"));
            resp
        }
        Err(e) => {
            log::warn!("php {}: {e}", script.display());
            text(StatusCode::INTERNAL_SERVER_ERROR, "fp-proxy: php failed")
        }
    }
}

/// Path characters that must be escaped when building the remote Legacy URL.
const PATH_ESC: &AsciiSet = &CONTROLS.add(b' ').add(b'"').add(b'#').add(b'<').add(b'>').add(b'?').add(b'`').add(b'{').add(b'}').add(b'%');

type Https = hyper_rustls::HttpsConnector<HttpConnector>;

fn client() -> &'static Client<Https, Empty<Bytes>> {
    static C: OnceLock<Client<Https, Empty<Bytes>>> = OnceLock::new();
    C.get_or_init(|| {
        let https = hyper_rustls::HttpsConnectorBuilder::new()
            .with_webpki_roots()
            .https_or_http()
            .enable_http1()
            .build();
        Client::builder(TokioExecutor::new()).build(https)
    })
}

/// Whether a URL (a redirect's target) is in one of the mounted packs.
fn in_mounts(mounts: &[Arc<crate::Mount>], url: &str) -> bool {
    let Ok(uri) = url.parse::<hyper::Uri>() else { return false };
    let Some(key) = uri.host().and_then(|h| Key::new(h, uri.path())) else { return false };
    let zk = key.zip_key();
    mounts.iter().any(|m| m.file(&zk).is_some())
}

/// Pass a request (method, content type, cookies, body) to the game's own site and its answer
/// back, following redirects (http to https above all) with the same request.
async fn live_forward(method: Method, headers: &hyper::HeaderMap, body: Bytes, rel: &str, q: &str) -> Option<Response<Body>> {
    static C: OnceLock<Client<Https, Full<Bytes>>> = OnceLock::new();
    let client = C.get_or_init(|| {
        let https = hyper_rustls::HttpsConnectorBuilder::new().with_webpki_roots().https_or_http().enable_http1().build();
        Client::builder(TokioExecutor::new()).build(https)
    });
    let mut url = format!("https://{rel}{q}");
    let mut empty_answers = 0;
    for _ in 0..6 {
        let uri: hyper::Uri = url.parse().ok()?;
        let mut b = Request::builder().method(method.clone()).uri(uri.clone());
        for name in [header::CONTENT_TYPE, header::COOKIE, header::ACCEPT, header::USER_AGENT, header::ACCEPT_LANGUAGE] {
            for v in headers.get_all(&name) {
                b = b.header(&name, v.clone());
            }
        }
        let resp = match client.request(b.body(Full::new(body.clone())).ok()?).await {
            Ok(r) => r,
            Err(e) => {
                log::warn!("live {method} failed: {url}: {e}");
                return None;
            }
        };
        if resp.status().is_redirection() {
            let loc = resp.headers().get(header::LOCATION)?.to_str().ok()?.to_string();
            url = if loc.starts_with("http") { loc } else {
                format!("{}://{}{}", uri.scheme_str().unwrap_or("https"), uri.authority()?.as_str(), loc)
            };
            continue;
        }
        let status = resp.status();
        let ct = resp.headers().get(header::CONTENT_TYPE).cloned();
        let cookies: Vec<HeaderValue> = resp.headers().get_all(header::SET_COOKIE).iter().cloned().collect();
        let bytes = resp.into_body().collect().await.ok()?.to_bytes();
        log::info!("live {method} {url}: {status} ({} bytes)", bytes.len());
        if status == StatusCode::OK && bytes.is_empty() && empty_answers < 2 && ask_again_when_empty(rel, &body) {
            empty_answers += 1;
            tokio::time::sleep(std::time::Duration::from_millis(500 * empty_answers)).await;
            continue;
        }
        log::debug!("live {method} sent {:?}; got {:?}", String::from_utf8_lossy(&body[..body.len().min(400)]),
            String::from_utf8_lossy(&bytes[..bytes.len().min(300)]));
        let mut r = with(status, "application/octet-stream", full(bytes));
        if let Some(ct) = ct {
            r.headers_mut().insert(header::CONTENT_TYPE, ct);
        }
        for c in cookies {
            r.headers_mut().append(header::SET_COOKIE, c);
        }
        return Some(r);
    }
    None
}

/// Happy Wheels' level server answers a level search with nothing now and then (about one in
/// six, checked 2026-11-23), and the game then shows no levels: such a search is asked again.
/// Searches only (action=get_...): asking again changes nothing on the server.
fn ask_again_when_empty(rel: &str, body: &[u8]) -> bool {
    rel.ends_with("get_level.hw") && String::from_utf8_lossy(body).split('&').any(|kv| kv.starts_with("action=get_"))
}

/// Fetch from the remote Legacy server, then (if allowed) the original site, and cache into htdocs.
async fn remote(srv: &GameServer, key: &Key, query: Option<&str>) -> Option<(Bytes, Option<HeaderValue>)> {
    let cfg = srv.config();
    if key.dir {
        return None;
    }
    let rel = utf8_percent_encode(&key.htdocs_rel(), PATH_ESC).to_string();
    let q = query.filter(|q| !q.is_empty()).map(|q| format!("?{q}")).unwrap_or_default();
    let mut found = None;
    if let Some(base) = cfg.legacy_base.as_deref() {
        let url = format!("{}{}{q}", if base.ends_with('/') { base.to_string() } else { format!("{base}/") }, rel);
        found = fetch(&url, "legacy").await;
    }
    // Only the archive's files are kept: a live site's answers change (and an empty one, as an
    // API address fetched as a page gives, would hide the real answer from then on).
    let mut keep = found.is_some();
    if found.is_none() && live_allowed(&cfg.live_domains, &rel) && !tracker(&rel) {
        found = fetch(&format!("http://{rel}{q}"), "live").await;
        keep = false;
    }
    let (bytes, ct) = found?;
    if cfg.cache_remote && keep && !bytes.is_empty() {
        if let Some(root) = cfg.htdocs {
            let dest = root.join(key.htdocs_rel());
            let b = bytes.clone();
            tokio::task::spawn_blocking(move || {
                if let Some(parent) = dest.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                let tmp = dest.with_extension("fp-part");
                if std::fs::write(&tmp, &b).is_ok() {
                    let _ = std::fs::rename(&tmp, &dest);
                }
            });
        }
    }
    Some((bytes, ct))
}

/// Script library CDNs pages load their code from (CreateJS, jQuery, ...): as needed as the
/// game's own files, and never ad loaders.
const LIBRARY_CDNS: &[&str] = &[
    "code.createjs.com", "ajax.googleapis.com", "cdnjs.cloudflare.com", "code.jquery.com",
    "cdn.jsdelivr.net", "unpkg.com", "fonts.googleapis.com", "fonts.gstatic.com",
    "ajax.aspnetcdn.com", "maxcdn.bootstrapcdn.com", "stackpath.bootstrapcdn.com",
    "netdna.bootstrapcdn.com", "use.fontawesome.com", "yui.yahooapis.com", "cdn.rawgit.com",
    "ajax.cdnjs.com", "d3js.org", "threejs.org", "pixijs.download", "cdn.phaser.io",
];

/// Whether the live web may be asked for `rel` (`<host>/<path>`): the game's own domains and
/// the script library CDNs, nothing else (ad networks above all).
fn live_allowed(domains: &[String], rel: &str) -> bool {
    if domains.is_empty() {
        return false;
    }
    let host = rel.split('/').next().unwrap_or("").to_ascii_lowercase();
    in_domains(domains, rel) || LIBRARY_CDNS.contains(&host.as_str())
}

/// Whether `rel` (`<host>/<path>`) is on one of `domains` or their subdomains.
fn in_domains(domains: &[String], rel: &str) -> bool {
    let host = rel.split('/').next().unwrap_or("").to_ascii_lowercase();
    domains.iter().any(|d| host == *d || host.ends_with(&format!(".{d}")))
}

/// Analytics and ad beacons old games still call: never sent to the live web.
fn tracker(rel: &str) -> bool {
    const HOSTS: &[&str] = &[
        "scorecardresearch.com", "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "cloudflareinsights.com", "quantserve.com", "omtrdc.net", "2o7.net", "hotjar.com", "facebook.net",
        "appspot.com/cto/", "go.com/b/ss/",
    ];
    let r = rel.to_ascii_lowercase();
    let host = r.split('/').next().unwrap_or("");
    HOSTS.iter().any(|h| if h.contains('/') { r.contains(h) } else { host == *h || host.ends_with(&format!(".{h}")) })
}

/// GET a URL, following up to three redirects. `what` names the source in the log.
async fn fetch(url: &str, what: &str) -> Option<(Bytes, Option<HeaderValue>)> {
    let mut url = url.to_string();
    for _ in 0..4 {
        let uri: hyper::Uri = url.parse().ok()?;
        let resp = match client().get(uri.clone()).await {
            Ok(r) => r,
            Err(e) => {
                log::warn!("{what} fetch failed: {url}: {e}");
                return None;
            }
        };
        if resp.status().is_redirection() {
            let loc = resp.headers().get(header::LOCATION)?.to_str().ok()?.to_string();
            url = if loc.starts_with("http") { loc } else {
                format!("{}://{}{}", uri.scheme_str().unwrap_or("http"), uri.authority()?.as_str(), loc)
            };
            continue;
        }
        if resp.status() != StatusCode::OK {
            log::info!("{what} miss {} {url}", resp.status());
            return None;
        }
        let ct = resp.headers().get(header::CONTENT_TYPE).cloned();
        let bytes = resp.into_body().collect().await.ok()?.to_bytes();
        if what == "live-first" {
            log::debug!("{what} hit {url} ({} bytes)", bytes.len());
        } else {
            log::info!("{what} hit {url} ({} bytes)", bytes.len());
        }
        return Some((bytes, ct));
    }
    None
}

// ---- bodies ----------------------------------------------------------------------------

/// Read from a blocking source on the blocking pool, handing chunks to the response body.
fn stream_blocking<F>(size: u64, produce: F) -> Body
where
    F: FnOnce(&mut dyn FnMut(Bytes) -> bool) -> io::Result<()> + Send + 'static,
{
    let (tx, rx) = mpsc::channel::<io::Result<Bytes>>(4);
    tokio::task::spawn_blocking(move || {
        let tx2 = tx.clone();
        let (mut sent, mut left) = (0u64, false);
        let mut send = |b: Bytes| {
            let n = b.len() as u64;
            let ok = tx2.blocking_send(Ok(b)).is_ok();
            if ok { sent += n } else { left = true }
            ok
        };
        match produce(&mut send) {
            Err(e) => {
                log::warn!("file stream failed after {sent} of {size} bytes: {e}");
                let _ = tx.blocking_send(Err(e));
            }
            Ok(()) if left => log::debug!("file stream: the reader left after {sent} of {size} bytes"),
            Ok(()) => {}
        }
    });
    ChanBody { rx, remaining: size }.boxed()
}

fn pump(r: &mut dyn Read, send: &mut dyn FnMut(Bytes) -> bool) -> io::Result<()> {
    let mut buf = vec![0u8; CHUNK];
    loop {
        let n = r.read(&mut buf)?;
        if n == 0 || !send(Bytes::copy_from_slice(&buf[..n])) {
            return Ok(());
        }
    }
}

struct ChanBody {
    rx: mpsc::Receiver<io::Result<Bytes>>,
    remaining: u64,
}

impl hyper::body::Body for ChanBody {
    type Data = Bytes;
    type Error = io::Error;

    fn poll_frame(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Result<Frame<Bytes>, io::Error>>> {
        match self.rx.poll_recv(cx) {
            Poll::Ready(Some(Ok(b))) => {
                self.remaining = self.remaining.saturating_sub(b.len() as u64);
                Poll::Ready(Some(Ok(Frame::data(b))))
            }
            Poll::Ready(Some(Err(e))) => Poll::Ready(Some(Err(e))),
            Poll::Ready(None) => Poll::Ready(None),
            Poll::Pending => Poll::Pending,
        }
    }

    fn size_hint(&self) -> SizeHint {
        SizeHint::with_exact(self.remaining)
    }
}

fn full(b: Bytes) -> Body {
    Full::new(b).map_err(|never| match never {}).boxed()
}

fn empty() -> Body {
    Empty::new().map_err(|never| match never {}).boxed()
}

fn with(status: StatusCode, ct: &str, body: Body) -> Response<Body> {
    let mut r = Response::new(body);
    *r.status_mut() = status;
    let h = r.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_str(ct).unwrap_or(HeaderValue::from_static("application/octet-stream")));
    h.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, HeaderValue::from_static("*"));
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    r
}

fn ok_file(name: &str, size: u64, body: Body) -> Response<Body> {
    file_response(&content_type(name), size, body)
}

fn file_response(ct: &str, size: u64, body: Body) -> Response<Body> {
    let mut r = with(StatusCode::OK, ct, body);
    r.headers_mut().insert(header::CONTENT_LENGTH, HeaderValue::from(size));
    r
}

fn text(status: StatusCode, msg: &'static str) -> Response<Body> {
    with(status, "text/plain", full(Bytes::from_static(msg.as_bytes())))
}


/// GET a URL with the game server's HTTPS client (gzip understood). For metadata sync and
/// other app-side fetches, so the app has one TLS stack.
pub async fn http_get(url: &str) -> io::Result<Bytes> {
    let uri: hyper::Uri = url.parse().map_err(io::Error::other)?;
    let req = Request::get(uri)
        .header(header::ACCEPT_ENCODING, "gzip")
        .header(header::USER_AGENT, "ARMFlash")
        .body(Empty::<Bytes>::new())
        .map_err(io::Error::other)?;
    let resp = client().request(req).await.map_err(io::Error::other)?;
    let status = resp.status();
    let gz = resp.headers().get(header::CONTENT_ENCODING).is_some_and(|v| v.as_bytes().eq_ignore_ascii_case(b"gzip"));
    let body = resp.into_body().collect().await.map_err(io::Error::other)?.to_bytes();
    if !status.is_success() {
        return Err(io::Error::other(format!("HTTP {status} for {url}")));
    }
    if gz {
        let mut out = Vec::with_capacity(body.len() * 4);
        flate2::read::GzDecoder::new(&body[..]).read_to_end(&mut out)?;
        return Ok(out.into());
    }
    Ok(body)
}

#[cfg(test)]
mod live_tests {
    use super::{ask_again_when_empty, live_allowed, tracker};

    #[test]
    fn happy_wheels_level_searches_are_asked_again() {
        let lv = "totaljerkface.com/get_level.hw";
        assert!(ask_again_when_empty(lv, b"action=get_all&page=1&sortby=newest&uploaded=week"));
        assert!(ask_again_when_empty(lv, b"page=1&action=get_featured"));
        assert!(!ask_again_when_empty(lv, b"action=rate_level&level_id=1&rating=5"));
        assert!(!ask_again_when_empty("totaljerkface.com/submit_level.hw", b"action=get_all"));
    }

    #[test]
    fn live_web_only_for_the_games_own_site() {
        // Hidden Room Escape: its own site may be asked, the ad network it calls may not.
        let hre = vec!["mirchigames.com".to_string()];
        assert!(live_allowed(&hre, "www.mirchigames.com/games/x.swf"));
        assert!(!live_allowed(&hre, "cdn.intergi.com/AdLoader.swf"));
        // Phineas and Ferb: the game is on img.lum.dolimg.com, its start-up script on a.dolimg.com.
        let pf = vec!["dolimg.com".to_string()];
        assert!(live_allowed(&pf, "a.dolimg.com/media/en-US/games/html5/gameConfig/isl.js"));
        assert!(!live_allowed(&pf, "notdolimg.com/x.js"));
        assert!(!live_allowed(&[], "a.dolimg.com/x.js"));
        // Elsa Preparing Anna's Wedding loads CreateJS from its CDN.
        let elsa = vec!["playdora.com".to_string()];
        assert!(live_allowed(&elsa, "code.createjs.com/createjs-2015.11.26.min.js"));
        assert!(tracker("b.scorecardresearch.com/b"));
        assert!(!tracker("a.dolimg.com/x.js"));
    }
}
