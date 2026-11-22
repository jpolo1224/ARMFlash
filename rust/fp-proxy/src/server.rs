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
                                async move { Ok::<_, Infallible>(handle(srv, port, req).await) }
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
fn handle_boxed(srv: Arc<GameServer>, port: u16, req: Request<Incoming>) -> Pin<Box<dyn std::future::Future<Output = Response<Body>> + Send>> {
    Box::pin(handle(srv, port, req))
}

async fn handle(srv: Arc<GameServer>, port: u16, req: Request<Incoming>) -> Response<Body> {
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
                async move { Ok::<_, Infallible>(handle_boxed(srv, port, req).await) }
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

    match srv.resolve(&key, query) {
        Resolved::Zip { mount, index, size, name } => {
            let body = if head {
                empty()
            } else if size <= SMALL {
                match tokio::task::spawn_blocking(move || mount.read_all(index, size)).await {
                    Ok(Ok(v)) => full(v.into()),
                    _ => return text(StatusCode::INTERNAL_SERVER_ERROR, "fp-proxy: zip read failed"),
                }
            } else {
                stream_blocking(size, move |send| {
                    let mut a = mount.reader();
                    let mut f = a.by_index(index).map_err(io::Error::other)?;
                    pump(&mut f, send)
                })
            };
            ok_file(&name, size, body)
        }
        Resolved::Disk(p) => {
            let size = std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0);
            let name = p.to_string_lossy().split('?').next().unwrap_or("").to_string();
            let body = if head {
                empty()
            } else {
                stream_blocking(size, move |send| pump(&mut std::fs::File::open(&p)?, send))
            };
            ok_file(&name, size, body)
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
    if found.is_none() && cfg.live_web && !tracker(&rel) {
        found = fetch(&format!("http://{rel}{q}"), "live").await;
    }
    let (bytes, ct) = found?;
    if cfg.cache_remote {
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
        log::info!("{what} hit {url} ({} bytes)", bytes.len());
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
        let mut send = |b: Bytes| tx2.blocking_send(Ok(b)).is_ok();
        if let Err(e) = produce(&mut send) {
            let _ = tx.blocking_send(Err(e));
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
    let mut r = with(StatusCode::OK, &content_type(name), body);
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
        .header(header::USER_AGENT, "FlashARM")
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
