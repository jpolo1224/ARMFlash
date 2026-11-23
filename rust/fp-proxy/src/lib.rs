//! fp-proxy: a Flashpoint-compatible game server for Android.
//!
//! It answers the same requests Flashpoint's game server does, with the same lookup rules,
//! but runs in-process as a library:
//!
//! 1. Mounted data-pack zips, in mount order (`content/<host>/<path>`, case-insensitive),
//!    streamed straight out of the zip. Nothing is unpacked to storage.
//! 2. A local htdocs folder (`<htdocs>/<host>/<path>`), for Legacy games.
//! 3. Optionally, the remote Legacy server (`<legacy_base><host>/<path>`), cached into htdocs.
//!
//! Browsers reach it as an HTTP forward proxy (WebView via androidx.webkit ProxyController).
//! Native players (Ruffle, ScummVM) skip HTTP and call [`GameServer::read`] directly.

mod mount;
mod path;
mod pfile;
pub mod php;
mod rewrite;
mod server;
pub mod zipseek;
mod tls;

use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};

pub use mount::Mount;
pub use path::Key;
pub use server::{http_get, start, ProxyHandle};
pub use zipseek::preparing;

#[derive(Clone, Debug, Default)]
pub struct Config {
    /// Local Legacy htdocs root. Files are looked up as `<htdocs>/<host>/<path>`.
    pub htdocs: Option<PathBuf>,
    /// Remote Legacy base URL, e.g. `https://infinity.flashpointarchive.org/Flashpoint/Legacy/htdocs/`.
    /// `None` keeps the game server fully offline, which is the default.
    pub legacy_base: Option<String>,
    /// Save files fetched from the remote Legacy server into `htdocs`.
    pub cache_remote: bool,
    /// Last resort for a file in neither the game's data nor Legacy: the original site, if it
    /// still serves it (the desktop game server's live-internet fallback). Some curations rely
    /// on it, such as Disney's HTML5 games, whose start-up script was never archived.
    /// Only for these sites (the game's own domain, e.g. "dolimg.com", with its subdomains):
    /// third-party ad and tracking hosts must keep failing as they do on desktop, or a game
    /// waits forever on an ad loader that comes back to life (Hidden Room Escape). Empty: off.
    pub live_domains: Vec<String>,
    /// Sites a game runs from live, before its pack (which is then the offline fallback): games
    /// whose online parts only work with the site's current version (Happy Wheels HTML5: its
    /// 2021 client and today's level server no longer fully understand each other).
    pub live_first: Vec<String>,
}

/// Where a request was found.
pub enum Resolved {
    Zip { mount: Arc<Mount>, index: usize, size: u64, name: String },
    Disk(PathBuf),
    /// The request named a directory without a trailing slash; redirect like the game server.
    AddSlash,
    NotFound,
}

#[derive(Default)]
pub struct GameServer {
    mounts: RwLock<Vec<Arc<Mount>>>,
    config: RwLock<Config>,
}

impl GameServer {
    pub fn new(config: Config) -> Arc<GameServer> {
        Arc::new(GameServer { mounts: RwLock::default(), config: RwLock::new(config) })
    }

    pub fn config(&self) -> Config {
        self.config.read().unwrap().clone()
    }

    pub fn set_config(&self, config: Config) {
        *self.config.write().unwrap() = config;
    }

    /// Mount a data pack. Mounting the same file twice is a no-op, as in the game server.
    /// Returns the number of files in the pack.
    pub fn mount(&self, zip: &Path) -> io::Result<usize> {
        if let Some(m) = self.mounts.read().unwrap().iter().find(|m| m.path == zip) {
            return Ok(m.len());
        }
        let started = std::time::Instant::now();
        let m = Arc::new(Mount::open(zip)?);
        let n = m.len();
        log::info!("mounted {} ({} files, {:?})", zip.display(), n, started.elapsed());
        let mut mounts = self.mounts.write().unwrap();
        if !mounts.iter().any(|x| x.path == zip) {
            mounts.push(m.clone());
        }
        drop(mounts);
        // A pack that runs PHP (Happy Wheels' level server): unpack its scripts and start indexing
        // its big data files now, while the game loads, instead of on its first request.
        if php::enabled() {
            let _ = std::thread::Builder::new().name("php-unpack".into()).spawn(move || {
                let extra = php::handler_exts(&m);
                if m.names().any(|name| php::is_script_in(name, &extra)) {
                    if let Err(e) = php::unpacked(&m) {
                        log::warn!("php: unpacking {}: {e}", m.path.display());
                    }
                }
            });
        }
        Ok(n)
    }

    pub fn unmount(&self, zip: &Path) -> bool {
        let mut mounts = self.mounts.write().unwrap();
        let before = mounts.len();
        mounts.retain(|m| m.path != zip);
        before != mounts.len()
    }

    pub fn unmount_all(&self) {
        self.mounts.write().unwrap().clear();
    }

    pub fn mounted(&self) -> Vec<PathBuf> {
        self.mounts.read().unwrap().iter().map(|m| m.path.clone()).collect()
    }

    /// Resolve against mounted zips and local htdocs. Never touches the network.
    pub fn resolve(&self, key: &Key, query: Option<&str>) -> Resolved {
        let mounts: Vec<Arc<Mount>> = self.mounts.read().unwrap().clone();
        let htdocs = self.config.read().unwrap().htdocs.clone();
        let zk = key.zip_key();

        if !key.dir {
            for m in &mounts {
                if let Some((index, size)) = m.file(&zk) {
                    return Resolved::Zip { mount: m.clone(), index, size, name: key.path.clone() };
                }
            }
            if let Some(root) = &htdocs {
                let rel = key.htdocs_rel();
                // The game server tries `path?query` first: some Legacy pages were saved
                // per query string.
                if let Some(q) = query.filter(|q| !q.is_empty()) {
                    let p = root.join(format!("{rel}?{q}"));
                    if p.is_file() {
                        return Resolved::Disk(p);
                    }
                }
                let p = root.join(&rel);
                if p.is_file() {
                    return Resolved::Disk(p);
                }
            }
        }

        let in_zip_dir = mounts.iter().any(|m| m.is_dir(&zk));
        let disk_dir = htdocs.as_ref().map(|r| r.join(key.htdocs_rel())).filter(|p| p.is_dir());
        if !in_zip_dir && disk_dir.is_none() {
            return Resolved::NotFound;
        }
        if !key.dir {
            return Resolved::AddSlash;
        }
        for idx in path::INDEX_FILES {
            let k = format!("{zk}/{idx}");
            for m in &mounts {
                if let Some((index, size)) = m.file(&k) {
                    let name = if key.path.is_empty() { idx.to_string() } else { format!("{}/{idx}", key.path) };
                    return Resolved::Zip { mount: m.clone(), index, size, name };
                }
            }
            if let Some(d) = &disk_dir {
                let p = d.join(idx);
                if p.is_file() {
                    return Resolved::Disk(p);
                }
            }
        }
        Resolved::NotFound
    }

    /// In-process read for native players: `url` is the absolute URL the content asked for.
    /// Local sources only.
    pub fn read(&self, url: &str) -> Option<Vec<u8>> {
        let (host, path, query) = split_url(url)?;
        let key = Key::new(host, path)?;
        match self.resolve(&key, query) {
            Resolved::Zip { mount, index, size, .. } => mount.read_all(index, size).ok(),
            Resolved::Disk(p) => std::fs::read(p).ok(),
            Resolved::AddSlash | Resolved::NotFound => None,
        }
    }
}

/// Split `scheme://host[:port]/path?query` into (host, path, query).
pub fn split_url(url: &str) -> Option<(&str, &str, Option<&str>)> {
    let rest = url.split_once("://").map(|(_, r)| r).unwrap_or(url);
    let rest = rest.split('#').next().unwrap_or(rest);
    let (hostport, pathq) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let (path, query) = match pathq.split_once('?') {
        Some((p, q)) => (p, Some(q)),
        None => (pathq, None),
    };
    Some((hostport, path, query))
}

/// The type of a file whose name says nothing (no extension, or one nobody registered), from its
/// first bytes, as desktop Flashpoint's game server finds it (Go's content sniffing): Happy
/// Wheels' HTML5 page is `happy_wheels.tjf`, and the browser only shows it as a page.
pub fn sniff(head: &[u8]) -> Option<&'static str> {
    let mut h = head;
    if h.starts_with(&[0xEF, 0xBB, 0xBF]) { h = &h[3..]; }
    let starts = |sig: &[u8]| h.len() >= sig.len() && &h[..sig.len()] == sig;
    if starts(b"GIF87a") || starts(b"GIF89a") { return Some("image/gif"); }
    if starts(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) { return Some("image/png"); }
    if starts(&[0xFF, 0xD8, 0xFF]) { return Some("image/jpeg"); }
    if starts(b"FWS") || starts(b"CWS") || starts(b"ZWS") { return Some("application/x-shockwave-flash"); }
    if starts(b"%PDF-") { return Some("application/pdf"); }
    if starts(b"OggS") { return Some("audio/ogg"); }
    if starts(b"ID3") { return Some("audio/mpeg"); }
    if starts(b"RIFF") && h.len() >= 12 && &h[8..12] == b"WAVE" { return Some("audio/wav"); }
    let t: Vec<u8> = h.iter().skip_while(|b| b.is_ascii_whitespace()).take(16).map(|b| b.to_ascii_lowercase()).collect();
    if t.starts_with(b"<?xml") { return Some("text/xml"); }
    const TAGS: &[&[u8]] = &[b"<!doctype html", b"<html", b"<head", b"<script", b"<iframe", b"<h1", b"<div", b"<font",
        b"<table", b"<a", b"<style", b"<title", b"<b", b"<body", b"<br", b"<p", b"<!--", b"<meta", b"<center"];
    for tag in TAGS {
        if t.starts_with(tag) && (tag == b"<!--" || t.get(tag.len()).is_none_or(|c| *c == b' ' || *c == b'>' || *c == b'\t' || *c == b'\n' || *c == b'\r')) {
            return Some("text/html");
        }
    }
    None
}

/// MIME type for a served file. Plugin formats first; the browser picks the player by them.
pub fn content_type(name: &str) -> String {
    let ext = name.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    let fixed = match ext.as_str() {
        "swf" | "spl" => "application/x-shockwave-flash",
        "dcr" | "dir" | "dxr" | "cct" | "cst" | "cxt" | "w3d" => "application/x-director",
        "unity3d" => "application/vnd.unity",
        "xap" => "application/x-silverlight-app",
        "jar" => "application/java-archive",
        "class" => "application/java-vm",
        "wasm" => "application/wasm",
        "js" | "mjs" => "text/javascript",
        "htm" | "html" | "php" | "php5" | "phtml" | "asp" | "aspx" | "jsp" => "text/html",
        "txt" | "ini" | "cfg" => "text/plain",
        "xml" => "text/xml",
        _ => "",
    };
    if !fixed.is_empty() {
        return fixed.to_string();
    }
    mime_guess::from_ext(&ext).first_or_octet_stream().essence_str().to_string()
}
