//! PHP for game pages, as desktop Flashpoint's Legacy server runs it (PHP 7 behind Apache):
//! scripts run through `php-cgi` with the request as CGI variables, the POST body on stdin, and
//! their `Status:` and headers turned back into the response.
//!
//! Scripts inside a game's zip are unpacked once, per mount, with the small text and data files
//! around them (what scripts `include` or read), into a private folder: the zip itself is never
//! unpacked whole. Scripts in the local htdocs run where they are.

use std::collections::HashMap;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Mutex, OnceLock, RwLock};
use std::time::Duration;

use bytes::Bytes;

use crate::Mount;

/// Where PHP is and how to start it (the app sets this once it has unpacked PHP).
#[derive(Clone, Debug)]
pub struct Php {
    /// The glibc loader that starts `php-cgi` (the APK's libldlinux.so).
    pub loader: PathBuf,
    /// The PHP folder: bin/php-cgi, lib/, ext/, conf.d/, php.ini.
    pub home: PathBuf,
    /// A private folder for unpacked scripts, sessions and uploads.
    pub work: PathBuf,
}

static PHP: RwLock<Option<Php>> = RwLock::new(None);

pub fn set(php: Option<Php>) {
    if let Some(p) = &php {
        let _ = std::fs::create_dir_all(p.work.join("tmp"));
        let _ = std::fs::create_dir_all(p.work.join("sessions"));
    }
    *PHP.write().unwrap() = php;
}

/// The private folder for unpacked and fetched scripts.
pub fn work_dir() -> Option<PathBuf> {
    PHP.read().unwrap().as_ref().map(|p| p.work.clone())
}

pub fn enabled() -> bool {
    PHP.read().unwrap().is_some()
}

/// Script extensions the desktop router hands to PHP.
pub fn is_script(path: &str) -> bool {
    let p = path.split('?').next().unwrap_or("").to_ascii_lowercase();
    p.ends_with(".php") || p.ends_with(".php5") || p.ends_with(".phtml")
}

/// Extensions a game's `.htaccess` files hand to PHP (`AddHandler application/x-httpd-php .hw`
/// or `AddType`), as Apache on desktop Flashpoint does: Happy Wheels' level server is `.hw`.
/// Read once per mount, from every `.htaccess` in it.
pub fn handler_exts(mount: &Mount) -> Vec<String> {
    static SEEN: OnceLock<Mutex<HashMap<PathBuf, Vec<String>>>> = OnceLock::new();
    let seen = SEEN.get_or_init(Default::default);
    if let Some(v) = seen.lock().unwrap().get(&mount.path) {
        return v.clone();
    }
    let mut exts = Vec::new();
    let mut a = mount.reader();
    for i in 0..a.len() {
        let Ok(mut f) = a.by_index(i) else { continue };
        let Ok(name) = f.name().map(|n| n.to_ascii_lowercase()) else { continue };
        if !name.ends_with("/.htaccess") || f.size() > 256 * 1024 {
            continue;
        }
        let mut text = String::new();
        if f.read_to_string(&mut text).is_err() {
            continue;
        }
        exts.extend(htaccess_php_exts(&text));
    }
    exts.sort();
    exts.dedup();
    if !exts.is_empty() {
        log::info!("php: {} hands {:?} to PHP", mount.path.display(), exts);
    }
    seen.lock().unwrap().insert(mount.path.clone(), exts.clone());
    exts
}

/// The extensions an `.htaccess` text maps to PHP ("hw", "html", ...).
pub fn htaccess_php_exts(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    for line in text.lines() {
        let words: Vec<&str> = line.split_whitespace().collect();
        if words.len() < 3 {
            continue;
        }
        let directive = words[0].to_ascii_lowercase();
        if (directive == "addhandler" || directive == "addtype") && words[1].to_ascii_lowercase().starts_with("application/x-httpd-php") {
            out.extend(words[2..].iter().map(|e| e.trim_start_matches('.').to_ascii_lowercase()).filter(|e| !e.is_empty()));
        }
    }
    out
}

/// A script by its extension or by its site's `.htaccess`.
pub fn is_script_in(path: &str, extra: &[String]) -> bool {
    if is_script(path) {
        return true;
    }
    let p = path.split('?').next().unwrap_or("").to_ascii_lowercase();
    let ext = p.rsplit('/').next().unwrap_or("").rsplit_once('.').map(|(_, e)| e.to_string()).unwrap_or_default();
    !ext.is_empty() && extra.iter().any(|e| *e == ext)
}

/// Files up to this size are unpacked for scripts; bigger data is read from the zip.
const SMALL_DATA: u64 = 4 << 20;

/// Data a script may open as a file: databases and the like.
fn is_data(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    [".sqlite", ".sqlite3", ".db", ".db3", ".dat", ".json", ".xml", ".txt", ".csv", ".bin"].iter().any(|e| n.ends_with(e))
}

/// What a script may need next to it: other scripts and the small files they read.
fn is_companion(name: &str, size: u64) -> bool {
    if size > SMALL_DATA {
        return false;
    }
    let n = name.to_ascii_lowercase();
    const EXT: &[&str] = &[
        ".php", ".php5", ".phtml", ".inc", ".txt", ".json", ".xml", ".ini", ".csv", ".dat", ".htm", ".html",
        ".tpl", ".cfg", ".conf", ".sql", ".sqlite", ".db", ".log", ".js",
    ];
    EXT.iter().any(|e| n.ends_with(e)) || !n.rsplit('/').next().unwrap_or("").contains('.')
}

/// The folder a mount's scripts were unpacked to (unpacking them the first time).
pub fn unpacked(mount: &Mount) -> io::Result<PathBuf> {
    static DONE: OnceLock<Mutex<HashMap<PathBuf, PathBuf>>> = OnceLock::new();
    let extra = handler_exts(mount);
    let mut done = DONE.get_or_init(Default::default).lock().unwrap();
    if let Some(d) = done.get(&mount.path) {
        return Ok(d.clone());
    }
    let work = PHP.read().unwrap().as_ref().map(|p| p.work.clone()).ok_or_else(|| io::Error::other("no php"))?;
    let id = {
        let mut h: u64 = 1469598103934665603;
        for b in mount.path.to_string_lossy().bytes() {
            h = (h ^ b as u64).wrapping_mul(1099511628211);
        }
        format!("{h:016x}")
    };
    let dir = work.join("zips").join(id);
    let _ = std::fs::remove_dir_all(&dir);
    let mut a = mount.reader();
    let (mut n, mut big) = (0, 0);
    for i in 0..a.len() {
        let mut f = match a.by_index(i) {
            Ok(f) => f,
            Err(_) => continue,
        };
        if f.is_dir() {
            continue;
        }
        let name = match f.name() {
            Ok(n) => n.replace('\\', "/"),
            Err(_) => continue,
        };
        if !name.to_ascii_lowercase().starts_with("content/") {
            continue;
        }
        let script = is_script_in(&name, &extra);
        let Some(rel) = f.enclosed_name() else { continue };
        let dest = dir.join(rel);
        if is_data(&name) && f.size() > SMALL_DATA {
            // Too big to unpack (Happy Wheels' 18 GB level database): PHP reads it from the zip.
            drop(f);
            crate::zipseek::register(&mount.path, i, &dest, &work)?;
            big += 1;
            continue;
        }
        if !(is_companion(&name, f.size()) || (script && f.size() <= SMALL_DATA)) {
            continue;
        }
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut out = Vec::with_capacity(f.size() as usize);
        f.read_to_end(&mut out)?;
        std::fs::write(&dest, out)?;
        n += 1;
    }
    log::info!("php: unpacked {n} scripts and files ({big} read from the zip) from {}", mount.path.display());
    done.insert(mount.path.clone(), dir.clone());
    Ok(dir)
}

/// One request for a script.
pub struct Request<'a> {
    pub method: &'a str,
    pub host: &'a str,
    /// The path as requested (`/dir/page.php`), without the query.
    pub path: &'a str,
    pub query: &'a str,
    pub https: bool,
    pub headers: Vec<(String, String)>,
    pub body: Bytes,
}

/// A script's answer.
pub struct Response {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Bytes,
}

/// Run `script` (in `doc_root`, the site's folder) for `req`.
pub async fn run(script: PathBuf, doc_root: PathBuf, req: Request<'_>) -> io::Result<Response> {
    let php = PHP.read().unwrap().clone().ok_or_else(|| io::Error::other("no php"))?;
    let mut env: Vec<(String, String)> = vec![
        ("GATEWAY_INTERFACE".into(), "CGI/1.1".into()),
        ("SERVER_SOFTWARE".into(), "Apache/2.4 (Flashpoint; ARMFlash)".into()),
        ("SERVER_PROTOCOL".into(), "HTTP/1.1".into()),
        ("SERVER_NAME".into(), req.host.split(':').next().unwrap_or("").into()),
        ("SERVER_PORT".into(), if req.https { "443" } else { "80" }.into()),
        ("SERVER_ADDR".into(), "127.0.0.1".into()),
        ("REMOTE_ADDR".into(), "127.0.0.1".into()),
        ("REMOTE_PORT".into(), "50000".into()),
        ("REQUEST_METHOD".into(), req.method.into()),
        ("REQUEST_URI".into(), if req.query.is_empty() { req.path.to_string() } else { format!("{}?{}", req.path, req.query) }),
        ("QUERY_STRING".into(), req.query.into()),
        ("SCRIPT_NAME".into(), req.path.into()),
        ("PHP_SELF".into(), req.path.into()),
        ("SCRIPT_FILENAME".into(), script.to_string_lossy().into()),
        ("DOCUMENT_ROOT".into(), doc_root.to_string_lossy().into()),
        ("REDIRECT_STATUS".into(), "200".into()),
        ("PHP_INI_SCAN_DIR".into(), php.home.join("conf.d").to_string_lossy().into()),
        ("TMPDIR".into(), php.work.join("tmp").to_string_lossy().into()),
        ("PATH".into(), "/system/bin".into()),
    ];
    if req.https {
        env.push(("HTTPS".into(), "on".into()));
    }
    for (k, v) in &req.headers {
        let k = k.to_ascii_uppercase().replace('-', "_");
        match k.as_str() {
            "CONTENT_TYPE" | "CONTENT_LENGTH" => env.push((k, v.clone())),
            _ => env.push((format!("HTTP_{k}"), v.clone())),
        }
    }
    if crate::zipseek::has_files() {
        // Big data files read from the zip (see zipseek): the preloaded library serves them.
        let lib = php.home.join("lib/libarmflash_zipfs.so");
        if lib.is_file() {
            env.push(("LD_PRELOAD".into(), lib.to_string_lossy().into()));
            env.push(("ARMFLASH_VFILES".into(), crate::zipseek::manifest_path(&php.work).to_string_lossy().into()));
            env.push(("ARMFLASH_ZIPREAD".into(), crate::zipseek::socket_path(&php.work).to_string_lossy().into()));
        }
    }
    if !req.body.is_empty() && !env.iter().any(|(k, _)| k == "CONTENT_LENGTH") {
        env.push(("CONTENT_LENGTH".into(), req.body.len().to_string()));
    }
    let work_dir = script.parent().map(Path::to_path_buf).unwrap_or_else(|| doc_root.clone());
    let s = |p: PathBuf| p.to_string_lossy().into_owned();
    let mut cmd = tokio::process::Command::new(&php.loader);
    cmd.arg("--library-path").arg(php.home.join("lib"))
        .arg(php.home.join("bin/php-cgi"))
        .arg("-c").arg(php.home.join("php.ini"))
        .arg("-d").arg(format!("extension_dir={}", s(php.home.join("ext"))))
        .arg("-d").arg(format!("session.save_path={}", s(php.work.join("sessions"))))
        .arg("-d").arg(format!("upload_tmp_dir={}", s(php.work.join("tmp"))))
        .env_clear().envs(env)
        .current_dir(work_dir)
        .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped())
        .kill_on_drop(true);
    let mut child = cmd.spawn()?;
    let body = req.body.clone();
    if let Some(mut si) = child.stdin.take() {
        use tokio::io::AsyncWriteExt;
        let _ = si.write_all(&body).await;
        drop(si);
    }
    // A first request can wait on a big file's seek index (a minute or two for gigabytes).
    let out = match tokio::time::timeout(Duration::from_secs(420), child.wait_with_output()).await {
        Ok(r) => r?,
        Err(_) => return Err(io::Error::new(io::ErrorKind::TimedOut, "php took too long")),
    };
    if !out.stderr.is_empty() {
        log::info!("php {}: {}", req.path, String::from_utf8_lossy(&out.stderr).trim());
    }
    parse(&out.stdout)
}

/// CGI output: headers, a blank line, the body.
fn parse(out: &[u8]) -> io::Result<Response> {
    let split = out.windows(4).position(|w| w == b"\r\n\r\n").map(|p| (p, p + 4))
        .or_else(|| out.windows(2).position(|w| w == b"\n\n").map(|p| (p, p + 2)));
    let (head, body) = match split {
        Some((h, b)) => (&out[..h], &out[b..]),
        None => (&out[..0], out),
    };
    let mut status = 200;
    let mut headers = Vec::new();
    for line in String::from_utf8_lossy(head).lines() {
        let Some((k, v)) = line.split_once(':') else { continue };
        let (k, v) = (k.trim(), v.trim());
        if k.eq_ignore_ascii_case("status") {
            status = v.split_whitespace().next().and_then(|c| c.parse().ok()).unwrap_or(200);
        } else {
            if k.eq_ignore_ascii_case("location") && status == 200 {
                status = 302;
            }
            headers.push((k.to_string(), v.to_string()));
        }
    }
    Ok(Response { status, headers, body: Bytes::copy_from_slice(body) })
}
