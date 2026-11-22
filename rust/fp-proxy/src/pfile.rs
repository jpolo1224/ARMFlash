//! A cloneable, lock-free file reader built on positional reads (pread).
//!
//! `zip::ZipArchive<R>` is `Clone` when `R: Clone`, and the clone shares the parsed
//! central directory through an `Arc`. Giving it a reader that clones in O(1) lets every
//! request work on its own archive handle: no mutex around the file, no re-parsing the
//! central directory, and any number of entries can stream at once.

use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::os::unix::fs::FileExt;
use std::sync::Arc;

#[derive(Clone)]
pub struct PFile {
    file: Arc<File>,
    len: u64,
    pos: u64,
}

impl PFile {
    pub fn open(path: &std::path::Path) -> io::Result<Self> {
        let file = File::open(path)?;
        let len = file.metadata()?.len();
        Ok(Self { file: Arc::new(file), len, pos: 0 })
    }
}

impl Read for PFile {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.pos >= self.len {
            return Ok(0);
        }
        let n = self.file.read_at(buf, self.pos)?;
        self.pos += n as u64;
        Ok(n)
    }
}

impl Seek for PFile {
    fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
        let next = match to {
            SeekFrom::Start(p) => Some(p),
            SeekFrom::End(d) => self.len.checked_add_signed(d),
            SeekFrom::Current(d) => self.pos.checked_add_signed(d),
        };
        match next {
            Some(p) => {
                self.pos = p;
                Ok(p)
            }
            None => Err(io::Error::new(io::ErrorKind::InvalidInput, "seek before start")),
        }
    }
}
