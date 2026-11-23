//! A mounted data pack: one game zip plus a case-insensitive index of its entries.

use std::collections::{HashMap, HashSet};
use std::io::{self, Read};
use std::path::{Path, PathBuf};

use zip::ZipArchive;

use crate::pfile::PFile;

pub struct Mount {
    pub path: PathBuf,
    archive: ZipArchive<PFile>,
    /// lowercased entry name -> (entry index, uncompressed size)
    files: HashMap<String, (usize, u64)>,
    /// lowercased directory prefixes, without the trailing slash (`content/host/dir`)
    dirs: HashSet<String>,
}

impl Mount {
    pub fn open(path: &Path) -> io::Result<Mount> {
        let archive = ZipArchive::new(PFile::open(path)?).map_err(io::Error::other)?;
        let mut files = HashMap::with_capacity(archive.len());
        let mut dirs = HashSet::new();
        let mut a = archive.clone();
        for i in 0..a.len() {
            let entry = match a.by_index_raw(i) {
                Ok(e) => e,
                Err(_) => continue,
            };
            let name = match entry.name() {
                Ok(n) => n.to_string(),
                Err(_) => String::from_utf8_lossy(entry.name_raw()).into_owned(),
            };
            let name = name.replace('\\', "/").to_lowercase();
            let size = entry.size();
            let is_dir = entry.is_dir();
            drop(entry);
            let trimmed = name.trim_end_matches('/');
            // Every parent of every entry is a directory, even when the zip has no
            // explicit directory entries (Flashpoint packs usually don't).
            let mut cut = trimmed.len();
            while let Some(p) = trimmed[..cut].rfind('/') {
                if !dirs.insert(trimmed[..p].to_string()) {
                    break;
                }
                cut = p;
            }
            if is_dir {
                dirs.insert(trimmed.to_string());
            } else {
                files.insert(trimmed.to_string(), (i, size));
            }
        }
        Ok(Mount { path: path.to_path_buf(), archive, files, dirs })
    }

    pub fn file(&self, key: &str) -> Option<(usize, u64)> {
        self.files.get(key).copied()
    }

    pub fn is_dir(&self, key: &str) -> bool {
        self.dirs.contains(key)
    }

    pub fn len(&self) -> usize {
        self.files.len()
    }

    /// Every file's lowercased name.
    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.files.keys().map(|k| k.as_str())
    }

    /// A private handle for streaming one entry. Cheap: shares the parsed central
    /// directory and the file descriptor.
    pub fn reader(&self) -> ZipArchive<PFile> {
        self.archive.clone()
    }

    /// Read a whole entry. For small files and for in-process callers (Ruffle).
    pub fn read_all(&self, index: usize, size_hint: u64) -> io::Result<Vec<u8>> {
        let mut a = self.reader();
        let mut f = a.by_index(index).map_err(io::Error::other)?;
        let mut out = Vec::with_capacity(size_hint.min(64 << 20) as usize);
        f.read_to_end(&mut out)?;
        Ok(out)
    }
}
