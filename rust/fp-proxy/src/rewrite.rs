//! Redirects from a pack's `.htaccess` files (Apache's `RewriteRule ... [R]`), as desktop
//! Flashpoint's Legacy server applies them. Happy Wheels HTML5 sends its level requests to the
//! Flash release's level server this way. Only rules that redirect are applied (an absolute URL,
//! or the R flag); rules behind a `RewriteCond` are left alone, as their conditions are not read.

use std::collections::HashMap;
use std::io::Read;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use regex::Regex;

use crate::mount::Mount;
use crate::path::Key;

struct Rule {
    /// The `.htaccess` folder below `content/`, lowercased, with a trailing slash (`host/dir/`).
    dir: String,
    pattern: Regex,
    target: String,
    code: u16,
}

fn rules(mount: &Mount) -> Arc<Vec<Rule>> {
    static SEEN: OnceLock<Mutex<HashMap<PathBuf, Arc<Vec<Rule>>>>> = OnceLock::new();
    let seen = SEEN.get_or_init(Default::default);
    if let Some(r) = seen.lock().unwrap().get(&mount.path) {
        return r.clone();
    }
    let mut out = Vec::new();
    let names: Vec<String> = mount.names().filter(|n| n.ends_with("/.htaccess") && n.starts_with("content/")).map(String::from).collect();
    for name in names {
        let Some((index, size)) = mount.file(&name) else { continue };
        if size > 256 * 1024 {
            continue;
        }
        let mut a = mount.reader();
        let Ok(mut f) = a.by_index(index) else { continue };
        let mut text = String::new();
        if f.read_to_string(&mut text).is_err() {
            continue;
        }
        let dir = name["content/".len()..name.len() - ".htaccess".len()].to_string();
        out.extend(parse(&dir, &text));
    }
    // Deeper folders first, as their .htaccess files override their parents'.
    out.sort_by_key(|r| std::cmp::Reverse(r.dir.len()));
    if !out.is_empty() {
        log::info!("rewrite: {} redirect rules in {}", out.len(), mount.path.display());
    }
    let out = Arc::new(out);
    seen.lock().unwrap().insert(mount.path.clone(), out.clone());
    out
}

fn parse(dir: &str, text: &str) -> Vec<Rule> {
    let mut out = Vec::new();
    let (mut on, mut cond) = (false, false);
    for line in text.lines() {
        let w: Vec<&str> = line.split_whitespace().collect();
        let Some(first) = w.first() else { continue };
        match first.to_ascii_lowercase().as_str() {
            "rewriteengine" => on = w.get(1).is_some_and(|v| v.eq_ignore_ascii_case("on")),
            "rewritecond" => cond = true,
            "rewriterule" => {
                if std::mem::take(&mut cond) || !on || w.len() < 3 || w[1].starts_with('!') || w[2].contains("%{") {
                    continue;
                }
                let flags: Vec<String> = w.get(3).map(|f| f.trim_matches(|c| c == '[' || c == ']').split(',').map(|f| f.trim().to_ascii_lowercase()).collect()).unwrap_or_default();
                let mut code = 0u16;
                for f in &flags {
                    if f == "r" {
                        code = 302;
                    } else if let Some(c) = f.strip_prefix("r=") {
                        code = c.parse().unwrap_or(302);
                    }
                }
                let target = w[2];
                let absolute = target.starts_with("http://") || target.starts_with("https://");
                if absolute && code == 0 {
                    code = 302;
                }
                if !(300..400).contains(&code) || !(absolute || target.starts_with('/')) {
                    continue;
                }
                let pattern = if flags.iter().any(|f| f == "nc") { format!("(?i){}", w[1]) } else { w[1].to_string() };
                if let Ok(pattern) = Regex::new(&pattern) {
                    out.push(Rule { dir: dir.to_string(), pattern, target: target.to_string(), code });
                }
            }
            _ => {}
        }
    }
    out
}

/// Apache's `$1`..`$9`: always one digit (`$1abc` is group 1, then "abc").
fn expand(target: &str, caps: &regex::Captures) -> String {
    let mut out = String::new();
    let mut chars = target.chars().peekable();
    while let Some(c) = chars.next() {
        match (c, chars.peek().and_then(|d| d.to_digit(10))) {
            ('$', Some(d)) => {
                chars.next();
                out.push_str(caps.get(d as usize).map(|m| m.as_str()).unwrap_or(""));
            }
            _ => out.push(c),
        }
    }
    out
}

/// Where a pack's `.htaccess` redirects this request: the status code and the new location.
pub fn redirect(mounts: &[Arc<Mount>], key: &Key, query: Option<&str>) -> Option<(u16, String)> {
    let full = format!("{}/{}", key.host, key.path);
    let lower = full.to_ascii_lowercase();
    for m in mounts {
        for r in rules(m).iter() {
            if !lower.starts_with(&r.dir) {
                continue;
            }
            let rel = &full[r.dir.len()..];
            let Some(caps) = r.pattern.captures(rel) else { continue };
            let mut to = expand(&r.target, &caps);
            if to.starts_with('/') {
                to = format!("http://{}{to}", key.host);
            }
            // As Apache: the request's query string carries over unless the target has its own.
            if let Some(q) = query.filter(|q| !q.is_empty() && !to.contains('?')) {
                to.push('?');
                to.push_str(q);
            }
            return Some((r.code, to));
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn happy_wheels_html5_levels() {
        let rules = parse("totaljerkface.com/", "RewriteEngine On\nRewriteRule (.*\\.hw) http://swf.totaljerkface.com/$1 [R=307]\n");
        assert_eq!(rules.len(), 1);
        let caps = rules[0].pattern.captures("get_level.hw").unwrap();
        assert_eq!(expand(&rules[0].target, &caps), "http://swf.totaljerkface.com/get_level.hw");
        assert_eq!(rules[0].code, 307);
        assert!(rules[0].pattern.captures("happy_wheels.tjf").is_none());
        // Conditions are not evaluated, so their rules are skipped; internal rewrites too.
        assert!(parse("a/", "RewriteEngine On\nRewriteCond %{REQUEST_FILENAME} !-f\nRewriteRule (.*) http://b/$1 [R]\n").is_empty());
        assert!(parse("a/", "RewriteEngine On\nRewriteRule ^x$ y.php [L]\n").is_empty());
        assert!(parse("a/", "RewriteRule (.*) http://b/$1 [R]\n").is_empty());
    }
}
