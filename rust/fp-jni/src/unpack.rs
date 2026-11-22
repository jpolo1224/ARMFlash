//! Unpacking downloads: the Windows runtime (.tar.zst) and Flashpoint's support packs (.zip).
//! Progress is the share of the archive read so far, polled from Kotlin.

use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

static DONE: AtomicU64 = AtomicU64::new(0);
static TOTAL: AtomicU64 = AtomicU64::new(0);

struct Counting<R>(R);

impl<R: Read> Read for Counting<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.0.read(buf)?;
        DONE.fetch_add(n as u64, Ordering::Relaxed);
        Ok(n)
    }
}

fn open(src: &str) -> Result<Counting<BufReader<File>>, String> {
    let f = File::open(src).map_err(|e| format!("{src}: {e}"))?;
    TOTAL.store(f.metadata().map(|m| m.len()).unwrap_or(0), Ordering::Relaxed);
    DONE.store(0, Ordering::Relaxed);
    Ok(Counting(BufReader::with_capacity(1 << 20, f)))
}

/// Unpack a .tar.zst into `dest`, keeping links and permissions. Entries cannot leave `dest`.
pub fn tar_zst(src: &str, dest: &str) -> Result<String, String> {
    let dec = ruzstd::decoding::StreamingDecoder::new(open(src)?).map_err(|e| e.to_string())?;
    let mut ar = tar::Archive::new(dec);
    ar.set_preserve_permissions(true);
    ar.set_overwrite(true);
    std::fs::create_dir_all(dest).map_err(|e| e.to_string())?;
    let mut n = 0u64;
    for entry in ar.entries().map_err(|e| e.to_string())? {
        let mut e = entry.map_err(|e| e.to_string())?;
        e.unpack_in(Path::new(dest)).map_err(|e| e.to_string())?;
        n += 1;
    }
    Ok(serde_json::json!({ "entries": n }).to_string())
}

/// Unpack a .zip into `dest` (support packs). Entries cannot leave `dest`.
pub fn zip(src: &str, dest: &str) -> Result<String, String> {
    let f = File::open(src).map_err(|e| format!("{src}: {e}"))?;
    TOTAL.store(f.metadata().map(|m| m.len()).unwrap_or(0), Ordering::Relaxed);
    DONE.store(0, Ordering::Relaxed);
    let mut ar = zip::ZipArchive::new(BufReader::with_capacity(1 << 20, f)).map_err(|e| e.to_string())?;
    let root = Path::new(dest);
    for i in 0..ar.len() {
        let mut e = ar.by_index(i).map_err(|e| e.to_string())?;
        DONE.fetch_add(e.compressed_size(), Ordering::Relaxed);
        let Some(rel) = e.enclosed_name() else { continue };
        let out = root.join(rel);
        if e.is_dir() {
            std::fs::create_dir_all(&out).map_err(|e| e.to_string())?;
            continue;
        }
        if let Some(p) = out.parent() { std::fs::create_dir_all(p).map_err(|e| e.to_string())?; }
        let mut w = File::create(&out).map_err(|e| format!("{}: {e}", out.display()))?;
        std::io::copy(&mut e, &mut w).map_err(|e| e.to_string())?;
    }
    Ok(serde_json::json!({ "entries": ar.len() }).to_string())
}

pub fn progress() -> Result<String, String> {
    Ok(serde_json::json!({ "done": DONE.load(Ordering::Relaxed), "total": TOTAL.load(Ordering::Relaxed) }).to_string())
}
