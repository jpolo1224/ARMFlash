//! Turning a request URL into the key Flashpoint data packs are indexed by.
//!
//! Flashpoint's game server maps `http://<host>/<path>` to `content/<host>/<path>` inside
//! each mounted zip. The lookup is case-insensitive (the archive was curated on Windows),
//! percent-escapes are decoded, the query string plays no part, and only the port is
//! stripped from the host.

use percent_encoding::percent_decode_str;

/// A normalized request: host, cleaned path, and whether it named a directory.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Key {
    pub host: String,
    /// Path below the host, cleaned, original case, no leading slash.
    pub path: String,
    pub dir: bool,
}

impl Key {
    pub fn new(host: &str, raw_path: &str) -> Option<Key> {
        let host = host.split(':').next().unwrap_or("").trim().to_ascii_lowercase();
        if host.is_empty() || host.contains('/') || host.contains("..") {
            return None;
        }
        let decoded = percent_decode_str(raw_path).decode_utf8_lossy();
        let dir = decoded.ends_with('/') || decoded.is_empty();
        let mut parts: Vec<&str> = Vec::new();
        for seg in decoded.split(['/', '\\']) {
            match seg {
                "" | "." => {}
                ".." => {
                    parts.pop();
                }
                s => parts.push(s),
            }
        }
        let path = parts.join("/");
        Some(Key { host, dir: dir || path.is_empty(), path })
    }

    /// `content/<host>/<path>` lowercased: the zip index key for a file.
    pub fn zip_key(&self) -> String {
        let mut k = String::with_capacity(9 + self.host.len() + self.path.len());
        k.push_str("content/");
        k.push_str(&self.host);
        if !self.path.is_empty() {
            k.push('/');
            k.push_str(&self.path.to_lowercase());
        }
        k
    }

    /// Path relative to an htdocs root, original case: `<host>/<path>`.
    pub fn htdocs_rel(&self) -> String {
        if self.path.is_empty() {
            self.host.clone()
        } else {
            format!("{}/{}", self.host, self.path)
        }
    }
}

/// Index documents tried for a directory request, in the order the game server lists them.
/// The game server lets the last match win; here the first match wins so `index.html`
/// beats `index.php`, which nothing on a phone can execute yet.
pub const INDEX_FILES: &[&str] = &[
    "index.html", "index.htm", "index.php", "index.php5", "index.phtml", "index.asp", "index.aspx",
    "index.jsp", "index.jspx",
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_like_the_game_server() {
        let k = Key::new("WWW.Example.com:80", "/Games/My%20Game/Main.SWF").unwrap();
        assert_eq!(k.zip_key(), "content/www.example.com/games/my game/main.swf");
        assert_eq!(k.htdocs_rel(), "www.example.com/Games/My Game/Main.SWF");
        assert!(!k.dir);
    }

    #[test]
    fn cleans_traversal_and_marks_dirs() {
        let k = Key::new("a.com", "/x/../../y/./z/").unwrap();
        assert_eq!(k.path, "y/z");
        assert!(k.dir);
        assert!(Key::new("a.com", "").unwrap().dir);
        assert!(Key::new("..", "/a").is_none());
    }
}
