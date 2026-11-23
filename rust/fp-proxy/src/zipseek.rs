//! Reading anywhere inside a big file in a game's zip without unpacking it.
//!
//! Some games keep a database next to their server scripts, and the scripts open it as a file:
//! Happy Wheels' level server queries `happywheels.sqlite3`, 18 GB unpacked (8.8 GB deflated in
//! the zip), far more than a phone can spare twice. A stored entry is read straight from the
//! zip. A deflated one gets a seek index, built once in one pass over the entry, as zlib's
//! zran example does: every [`SPAN`] bytes of output, where a deflate block starts, the
//! position in the compressed data (with its bit offset) and the 32 KB of output before it, so
//! decompression can start there. A read then unpacks at most one span, and recently unpacked
//! spans are kept in [`CACHE`].
//!
//! PHP reaches these files through `libarmflash_zipfs.so` (tools/winrt/zipfs), preloaded into
//! php-cgi: it shows each one as an empty placeholder file of the right size and asks [`serve`]'s
//! socket for the bytes.

use std::collections::VecDeque;
use std::fs::File;
use std::io::{self, Read, Write};
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock, RwLock};

use libz_rs_sys as z;

/// Output bytes between seek points.
const SPAN: u64 = 4 << 20;
const WINDOW: usize = 32768;
/// Unpacked spans kept, per file.
const CACHE: usize = 16;
const MAGIC: &[u8; 8] = b"AFZIDX01";

struct Point {
    out: u64,
    inp: u64,
    bits: u8,
    /// Where this point's window is in the index file.
    window_at: u64,
}

struct Index {
    points: Vec<Point>,
    file: File,
}

pub struct Seekable {
    zip: File,
    data_start: u64,
    csize: u64,
    pub size: u64,
    deflated: bool,
    index_path: PathBuf,
    index: OnceLock<Result<Index, String>>,
    building: Mutex<()>,
    cache: Mutex<VecDeque<(usize, Arc<Vec<u8>>)>>,
}

impl Seekable {
    /// Entry `index` of the zip at `zip`. Its seek index (deflated entries) lives in `work`.
    pub fn open(zip: &Path, index: usize, work: &Path) -> io::Result<Seekable> {
        let f = File::open(zip)?;
        let mut a = zip::ZipArchive::new(crate::pfile::PFile::open(zip)?).map_err(io::Error::other)?;
        let e = a.by_index_raw(index).map_err(io::Error::other)?;
        let deflated = match e.compression() {
            zip::CompressionMethod::Stored => false,
            zip::CompressionMethod::Deflated => true,
            m => return Err(io::Error::other(format!("cannot seek in a {m:?} entry"))),
        };
        let data_start = e.data_start().ok_or_else(|| io::Error::other("entry has no data position"))?;
        let (csize, size) = (e.compressed_size(), e.size());
        let meta = std::fs::metadata(zip)?;
        let mtime = meta.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0);
        let mut h: u64 = 1469598103934665603;
        for b in format!("{}|{}|{}|{}|{}", zip.display(), meta.len(), mtime, index, data_start).bytes() {
            h = (h ^ b as u64).wrapping_mul(1099511628211);
        }
        let dir = work.join("zipindex");
        std::fs::create_dir_all(&dir)?;
        Ok(Seekable {
            zip: f,
            data_start,
            csize,
            size,
            deflated,
            index_path: dir.join(format!("{h:016x}.idx")),
            index: OnceLock::new(),
            building: Mutex::new(()),
            cache: Mutex::new(VecDeque::new()),
        })
    }

    /// Fill `buf` from `off` (fewer bytes at the end of the file).
    pub fn read_at(&self, off: u64, buf: &mut [u8]) -> io::Result<usize> {
        if off >= self.size || buf.is_empty() {
            return Ok(0);
        }
        let n = buf.len().min((self.size - off) as usize);
        if !self.deflated {
            self.zip.read_exact_at(&mut buf[..n], self.data_start + off)?;
            return Ok(n);
        }
        let idx = self.index()?;
        let mut done = 0;
        while done < n {
            let at = off + done as u64;
            let i = match idx.points.binary_search_by(|p| p.out.cmp(&at)) {
                Ok(i) => i,
                Err(i) => i - 1,
            };
            let span = self.span(idx, i)?;
            let from = (at - idx.points[i].out) as usize;
            let take = (n - done).min(span.len() - from);
            if take == 0 {
                return Err(io::Error::other("seek index does not cover the read"));
            }
            buf[done..done + take].copy_from_slice(&span[from..from + take]);
            done += take;
        }
        Ok(n)
    }

    /// Build the seek index now (or load it), so the first read does not wait for it.
    pub fn prepare(&self) -> io::Result<()> {
        if self.deflated { self.index().map(|_| ()) } else { Ok(()) }
    }

    fn index(&self) -> io::Result<&Index> {
        if let Some(r) = self.index.get() {
            return r.as_ref().map_err(|e| io::Error::other(e.clone()));
        }
        let _one = self.building.lock().unwrap();
        let r = self.index.get_or_init(|| self.load_or_build().map_err(|e| e.to_string()));
        r.as_ref().map_err(|e| io::Error::other(e.clone()))
    }

    fn load_or_build(&self) -> io::Result<Index> {
        if let Ok(i) = self.load() {
            return Ok(i);
        }
        let started = std::time::Instant::now();
        log::info!("zipseek: indexing {} MB deflated into {} MB", self.csize >> 20, self.size >> 20);
        let tmp = self.index_path.with_extension("part");
        let points = self.build(&tmp)?;
        std::fs::rename(&tmp, &self.index_path)?;
        log::info!("zipseek: {} seek points in {:?}", points, started.elapsed());
        self.load()
    }

    /// Index file: magic, point count, then per point out, inp (u64), bits (u8) and its window.
    fn load(&self) -> io::Result<Index> {
        let mut f = File::open(&self.index_path)?;
        let mut head = [0u8; 16];
        f.read_exact(&mut head)?;
        if &head[..8] != MAGIC {
            return Err(io::Error::other("not a seek index"));
        }
        let n = u64::from_le_bytes(head[8..16].try_into().unwrap()) as usize;
        let mut table = vec![0u8; n * 17];
        f.read_exact(&mut table)?;
        let base = 16 + table.len() as u64;
        let points = (0..n).map(|i| {
            let t = &table[i * 17..i * 17 + 17];
            Point {
                out: u64::from_le_bytes(t[0..8].try_into().unwrap()),
                inp: u64::from_le_bytes(t[8..16].try_into().unwrap()),
                bits: t[16],
                window_at: base + (i * WINDOW) as u64,
            }
        }).collect::<Vec<_>>();
        if points.is_empty() || points[0].out != 0 {
            return Err(io::Error::other("empty seek index"));
        }
        Ok(Index { points, file: f })
    }

    /// One pass over the entry, as zlib's zran: a point at the first block after every SPAN of
    /// output, with the window (the last 32 KB of output) it needs.
    fn build(&self, dest: &Path) -> io::Result<usize> {
        let mut windows = File::create(dest.with_extension("windows"))?;
        let mut table: Vec<u8> = Vec::new();
        let mut count = 0usize;
        let mut win = vec![0u8; WINDOW];
        // The stream's start is a seek point of its own: byte-aligned, nothing before it. (zran
        // gets its first point from the zlib header; a zip's raw deflate has none.)
        windows.write_all(&win)?;
        table.extend_from_slice(&0u64.to_le_bytes());
        table.extend_from_slice(&0u64.to_le_bytes());
        table.push(0);
        count += 1;
        let mut inbuf = vec![0u8; 1 << 20];
        let (mut totin, mut totout, mut last) = (0u64, 0u64, 0u64);
        let mut pos = self.data_start;
        let end = self.data_start + self.csize;
        let mut next_log = 1u64 << 30;
        unsafe {
            let mut s: z::z_stream = std::mem::zeroed();
            if z::inflateInit2_(&mut s, -15, z::zlibVersion(), std::mem::size_of::<z::z_stream>() as i32) != z::Z_OK {
                return Err(io::Error::other("inflateInit2 failed"));
            }
            let result = (|| -> io::Result<()> {
                loop {
                    if s.avail_in == 0 && pos < end {
                        let want = inbuf.len().min((end - pos) as usize);
                        self.zip.read_exact_at(&mut inbuf[..want], pos)?;
                        pos += want as u64;
                        s.next_in = inbuf.as_ptr();
                        s.avail_in = want as u32;
                    }
                    if s.avail_out == 0 {
                        s.next_out = win.as_mut_ptr();
                        s.avail_out = WINDOW as u32;
                    }
                    let (ai, ao) = (s.avail_in, s.avail_out);
                    let ret = z::inflate(&mut s, z::Z_BLOCK);
                    totin += (ai - s.avail_in) as u64;
                    totout += (ao - s.avail_out) as u64;
                    if ret == z::Z_STREAM_END {
                        return Ok(());
                    }
                    // Out of input with nothing left to give: the entry is cut short.
                    if ret == z::Z_BUF_ERROR && ai == s.avail_in && ao == s.avail_out && pos >= end {
                        return Err(io::Error::other("entry ended before its deflate stream"));
                    }
                    if ret != z::Z_OK && ret != z::Z_BUF_ERROR {
                        return Err(io::Error::other(format!("inflate error {ret} at {totin}")));
                    }
                    if (s.data_type & 128) != 0 && (s.data_type & 64) == 0 && totout - last > SPAN {
                        let left = s.avail_out as usize;
                        windows.write_all(&win[WINDOW - left..])?;
                        windows.write_all(&win[..WINDOW - left])?;
                        table.extend_from_slice(&totout.to_le_bytes());
                        table.extend_from_slice(&totin.to_le_bytes());
                        table.push((s.data_type & 7) as u8);
                        count += 1;
                        last = totout;
                    }
                    if totout >= next_log {
                        log::info!("zipseek: {} of {} MB", totout >> 20, self.size >> 20);
                        next_log += 1 << 30;
                    }
                }
            })();
            z::inflateEnd(&mut s);
            result?;
        }
        if totout != self.size {
            return Err(io::Error::other(format!("unpacked {totout} bytes, expected {}", self.size)));
        }
        drop(windows);
        let mut out = File::create(dest)?;
        out.write_all(MAGIC)?;
        out.write_all(&(count as u64).to_le_bytes())?;
        out.write_all(&table)?;
        io::copy(&mut File::open(dest.with_extension("windows"))?, &mut out)?;
        drop(out);
        let _ = std::fs::remove_file(dest.with_extension("windows"));
        Ok(count)
    }

    /// The output from point `i` to the next one (or the end), unpacked, through the cache.
    fn span(&self, idx: &Index, i: usize) -> io::Result<Arc<Vec<u8>>> {
        if let Some((_, v)) = self.cache.lock().unwrap().iter().find(|(k, _)| *k == i) {
            return Ok(v.clone());
        }
        let p = &idx.points[i];
        let len = idx.points.get(i + 1).map(|n| n.out).unwrap_or(self.size) - p.out;
        let mut window = vec![0u8; WINDOW];
        idx.file.read_exact_at(&mut window, p.window_at)?;
        let mut out = vec![0u8; len as usize];
        let mut inbuf = vec![0u8; 256 << 10];
        let mut pos = self.data_start + p.inp - (p.bits > 0) as u64;
        let end = self.data_start + self.csize;
        unsafe {
            let mut s: z::z_stream = std::mem::zeroed();
            if z::inflateInit2_(&mut s, -15, z::zlibVersion(), std::mem::size_of::<z::z_stream>() as i32) != z::Z_OK {
                return Err(io::Error::other("inflateInit2 failed"));
            }
            let result = (|| -> io::Result<()> {
                if p.bits > 0 {
                    let mut b = [0u8; 1];
                    self.zip.read_exact_at(&mut b, pos)?;
                    pos += 1;
                    z::inflatePrime(&mut s, p.bits as i32, (b[0] >> (8 - p.bits)) as i32);
                }
                if p.out > 0 {
                    z::inflateSetDictionary(&mut s, window.as_ptr(), WINDOW as u32);
                }
                s.next_out = out.as_mut_ptr();
                s.avail_out = len as u32;
                while s.avail_out > 0 {
                    if s.avail_in == 0 {
                        let want = inbuf.len().min((end - pos) as usize);
                        if want == 0 {
                            break;
                        }
                        self.zip.read_exact_at(&mut inbuf[..want], pos)?;
                        pos += want as u64;
                        s.next_in = inbuf.as_ptr();
                        s.avail_in = want as u32;
                    }
                    let ret = z::inflate(&mut s, z::Z_NO_FLUSH);
                    if ret == z::Z_STREAM_END {
                        break;
                    }
                    if ret != z::Z_OK && ret != z::Z_BUF_ERROR {
                        return Err(io::Error::other(format!("inflate error {ret}")));
                    }
                }
                if s.avail_out != 0 {
                    return Err(io::Error::other("span ended early"));
                }
                Ok(())
            })();
            z::inflateEnd(&mut s);
            result?;
        }
        let v = Arc::new(out);
        let mut c = self.cache.lock().unwrap();
        c.push_front((i, v.clone()));
        c.truncate(CACHE);
        Ok(v)
    }
}

// ---- the files PHP sees, and the socket it reads them through ------------------------------

static FILES: RwLock<Vec<(PathBuf, Arc<Seekable>)>> = RwLock::new(Vec::new());
static SOCKET: OnceLock<PathBuf> = OnceLock::new();

/// Show entry `index` of `zip` to PHP at `placeholder` (an empty file made here). Returns the
/// manifest of every such file (id, size, path per line), for the preloaded library.
pub fn register(zip: &Path, index: usize, placeholder: &Path, work: &Path) -> io::Result<()> {
    let s = Arc::new(Seekable::open(zip, index, work)?);
    if let Some(parent) = placeholder.parent() {
        std::fs::create_dir_all(parent)?;
    }
    // Read-only, so SQLite opens it read-only and nothing is ever written to the placeholder.
    let _ = std::fs::remove_file(placeholder);
    File::create(placeholder)?;
    std::fs::set_permissions(placeholder, std::os::unix::fs::PermissionsExt::from_mode(0o444))?;
    {
        let mut files = FILES.write().unwrap();
        files.retain(|(p, _)| p != placeholder);
        files.push((placeholder.to_path_buf(), s.clone()));
    }
    write_manifest(work)?;
    serve(work)?;
    // The seek index takes a minute or two for gigabytes: start it now, not on the first read.
    std::thread::Builder::new().name("zipseek-index".into()).spawn(move || {
        if let Err(e) = s.prepare() {
            log::warn!("zipseek: {e}");
        }
    })?;
    Ok(())
}

pub fn manifest_path(work: &Path) -> PathBuf {
    work.join("vfiles.tsv")
}

pub fn has_files() -> bool {
    !FILES.read().unwrap().is_empty()
}

fn write_manifest(work: &Path) -> io::Result<()> {
    let files = FILES.read().unwrap();
    let text: String = files.iter().enumerate().map(|(i, (p, s))| format!("{i}\t{}\t{}\n", s.size, p.display())).collect();
    let tmp = work.join("vfiles.tsv.part");
    std::fs::write(&tmp, text)?;
    std::fs::rename(tmp, manifest_path(work))
}

pub fn socket_path(work: &Path) -> PathBuf {
    work.join("zipread.sock")
}

/// The socket the preloaded library asks: request = id (u32), length (u32), offset (u64), all
/// little-endian; answer = count (i32, negative errno on failure) and that many bytes.
fn serve(work: &Path) -> io::Result<()> {
    if SOCKET.get().is_some() {
        return Ok(());
    }
    let path = socket_path(work);
    let _ = std::fs::remove_file(&path);
    let listener = std::os::unix::net::UnixListener::bind(&path)?;
    let _ = SOCKET.set(path);
    std::thread::Builder::new().name("zipseek-serve".into()).spawn(move || {
        for conn in listener.incoming() {
            let Ok(mut conn) = conn else { continue };
            let _ = std::thread::Builder::new().name("zipseek-conn".into()).spawn(move || {
                let mut req = [0u8; 16];
                let mut buf = Vec::new();
                while conn.read_exact(&mut req).is_ok() {
                    let id = u32::from_le_bytes(req[0..4].try_into().unwrap()) as usize;
                    let len = (u32::from_le_bytes(req[4..8].try_into().unwrap()) as usize).min(8 << 20);
                    let off = u64::from_le_bytes(req[8..16].try_into().unwrap());
                    let file = FILES.read().unwrap().get(id).map(|(_, s)| s.clone());
                    buf.resize(len, 0);
                    let r = match file {
                        Some(s) => s.read_at(off, &mut buf).map(|n| n as i32).unwrap_or_else(|e| {
                            log::warn!("zipseek read {id} at {off}: {e}");
                            -5
                        }),
                        None => -9,
                    };
                    if conn.write_all(&r.to_le_bytes()).is_err() {
                        break;
                    }
                    if r > 0 && conn.write_all(&buf[..r as usize]).is_err() {
                        break;
                    }
                }
            });
        }
    })?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Random reads through the seek index against the original file. Set ZIPSEEK_TEST_ZIP (a zip
    /// whose entry 1 is deflated) and ZIPSEEK_TEST_RAW (that entry unpacked).
    #[test]
    fn random_reads_match() {
        let (Ok(zip), Ok(raw)) = (std::env::var("ZIPSEEK_TEST_ZIP"), std::env::var("ZIPSEEK_TEST_RAW")) else { return };
        let work = tempfile::tempdir().unwrap();
        let s = Seekable::open(Path::new(&zip), 1, work.path()).unwrap();
        let raw = std::fs::read(raw).unwrap();
        assert_eq!(s.size as usize, raw.len());
        let t = std::time::Instant::now();
        s.prepare().unwrap();
        eprintln!("index built in {:?}", t.elapsed());
        let mut seed = 12345u64;
        for _ in 0..400 {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let off = (seed >> 11) % raw.len() as u64;
            let len = ((seed >> 40) % 70_000) as usize + 1;
            let mut buf = vec![0u8; len];
            let n = s.read_at(off, &mut buf).unwrap();
            let end = (off as usize + len).min(raw.len());
            assert_eq!(n, end - off as usize);
            assert!(buf[..n] == raw[off as usize..end], "mismatch at {off}+{len}");
        }
        // A second Seekable loads the index from disk instead of building it.
        let s2 = Seekable::open(Path::new(&zip), 1, work.path()).unwrap();
        let mut buf = vec![0u8; 4096];
        s2.read_at(raw.len() as u64 / 2, &mut buf).unwrap();
        assert!(buf[..] == raw[raw.len() / 2..raw.len() / 2 + 4096]);
    }

    /// What the preloaded library sees: a read-only empty placeholder, a manifest line, and reads
    /// over the socket that match the original file. Same variables as `random_reads_match`.
    #[test]
    fn socket_reads_match() {
        use std::os::unix::fs::PermissionsExt;
        let (Ok(zip), Ok(raw)) = (std::env::var("ZIPSEEK_TEST_ZIP"), std::env::var("ZIPSEEK_TEST_RAW")) else { return };
        let work = tempfile::tempdir().unwrap();
        let placeholder = work.path().join("zips/x/content/site/data.sqlite3");
        register(Path::new(&zip), 1, &placeholder, work.path()).unwrap();
        let raw = std::fs::read(raw).unwrap();
        let meta = std::fs::metadata(&placeholder).unwrap();
        assert_eq!(meta.len(), 0);
        assert_eq!(meta.permissions().mode() & 0o777, 0o444);
        let manifest = std::fs::read_to_string(manifest_path(work.path())).unwrap();
        let line = manifest.lines().find(|l| l.ends_with(&*placeholder.to_string_lossy())).unwrap();
        let mut f = line.split('\t');
        let id: u32 = f.next().unwrap().parse().unwrap();
        assert_eq!(f.next().unwrap().parse::<usize>().unwrap(), raw.len());
        let mut conn = std::os::unix::net::UnixStream::connect(socket_path(work.path())).unwrap();
        let mut seed = 777u64;
        for i in 0..300 {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            // The library asks for 256 KB blocks and for whole big reads; past the end returns 0.
            let off = if i == 0 { raw.len() as u64 } else { (seed >> 11) % raw.len() as u64 };
            let len = if i % 3 == 0 { 256 << 10 } else { ((seed >> 40) % 300_000) as u32 + 1 };
            let mut req = [0u8; 16];
            req[0..4].copy_from_slice(&id.to_le_bytes());
            req[4..8].copy_from_slice(&len.to_le_bytes());
            req[8..16].copy_from_slice(&off.to_le_bytes());
            conn.write_all(&req).unwrap();
            let mut n = [0u8; 4];
            conn.read_exact(&mut n).unwrap();
            let n = i32::from_le_bytes(n);
            let end = (off as usize + len as usize).min(raw.len());
            assert_eq!(n as usize, end.saturating_sub(off as usize), "count at {off}+{len}");
            let mut got = vec![0u8; n as usize];
            conn.read_exact(&mut got).unwrap();
            assert!(got[..] == raw[off as usize..end], "bytes at {off}+{len}");
        }
        // An unknown id answers -9 and the connection stays usable.
        let mut req = [0u8; 16];
        req[0..4].copy_from_slice(&999u32.to_le_bytes());
        req[4..8].copy_from_slice(&16u32.to_le_bytes());
        conn.write_all(&req).unwrap();
        let mut n = [0u8; 4];
        conn.read_exact(&mut n).unwrap();
        assert_eq!(i32::from_le_bytes(n), -9);
    }
}
