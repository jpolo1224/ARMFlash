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
mod server;
mod tls;

use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};

pub use mount::Mount;
pub use path::Key;
pub use server::{http_get, start, ProxyHandle};

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
    pub live_web: bool,
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
            mounts.push(m);
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
