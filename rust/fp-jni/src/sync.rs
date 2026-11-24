//! Metadata sync from Flashpoint's metadata server (FPFSS), the same steps and endpoints as
//! the desktop launcher's `src/back/sync.ts`, applied with flashpoint-archive's own update
//! functions. A fresh sync builds a complete flashpoint.sqlite on the phone, so the app
//! needs no desktop install; later syncs fetch only what changed.

use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

use flashpoint_archive::game::GameRedirect;
use flashpoint_archive::update::{RemoteCategory, RemoteDeletedGamesRes, RemoteGamesRes, RemotePlatform, RemoteTag};
use flashpoint_archive::FlashpointArchive;
use serde::Deserialize;
use serde_json::json;

use crate::core::{rt, Res};

/// Owner name the launcher derives from the source name "Flashpoint Archive".
const OWNER: &str = "flashpoint-archive";

#[derive(Default)]
struct Progress {
    phase: String,
    error: Option<String>,
    last_update: Option<String>,
}

fn progress() -> &'static Mutex<Progress> {
    static P: OnceLock<Mutex<Progress>> = OnceLock::new();
    P.get_or_init(|| Mutex::new(Progress::default()))
}
static RUNNING: AtomicBool = AtomicBool::new(false);
static CANCEL: AtomicBool = AtomicBool::new(false);
static DONE: AtomicI64 = AtomicI64::new(0);
static TOTAL: AtomicI64 = AtomicI64::new(0);
/// Bytes downloaded so far (as sent, gzipped), for the progress readout.
static BYTES: AtomicU64 = AtomicU64::new(0);

fn phase(p: &str) {
    progress().lock().unwrap().phase = p.to_string();
    log::info!("sync: {p}");
}

pub fn status() -> Res {
    let p = progress().lock().unwrap();
    Ok(json!({
        "running": RUNNING.load(Ordering::SeqCst),
        "phase": p.phase,
        "done": DONE.load(Ordering::SeqCst),
        "total": TOTAL.load(Ordering::SeqCst),
        "bytes": BYTES.load(Ordering::SeqCst),
        "failure": p.error,
        "lastUpdate": p.last_update,
    })
    .to_string())
}

pub fn cancel() {
    CANCEL.store(true, Ordering::SeqCst);
}

#[derive(Deserialize)]
struct RawTag {
    id: i64,
    name: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    category: String,
    date_modified: String,
    #[serde(default)]
    aliases: String,
    #[serde(rename = "Deleted", default)]
    deleted: bool,
}

#[derive(Deserialize)]
struct RawTags {
    tags: Vec<RawTag>,
    categories: Vec<RemoteCategory>,
}

#[derive(Deserialize)]
struct RawPlatform {
    id: i64,
    name: String,
    #[serde(default)]
    description: String,
    date_modified: String,
    #[serde(default)]
    aliases: String,
    #[serde(rename = "Deleted", default)]
    deleted: bool,
}

#[derive(Deserialize)]
struct RawRedirect {
    source_id: String,
    id: String,
}

fn aliases(s: &str) -> Vec<String> {
    s.split(';').map(|a| a.trim().to_string()).filter(|a| !a.is_empty()).collect()
}

fn get_json<T: serde::de::DeserializeOwned>(url: &str) -> Result<T, String> {
    let (bytes, wire) = rt().block_on(fp_proxy::http_get_sized(url)).map_err(|e| e.to_string())?;
    BYTES.fetch_add(wire, Ordering::SeqCst);
    serde_json::from_slice(&bytes).map_err(|e| format!("{url}: {e}"))
}

fn later(a: &str, b: &str) -> bool {
    // RFC 3339 timestamps from the server compare correctly as strings once normalised to UTC Z.
    a > b
}

/// Run a sync into the open database. `after` is the last update time from the previous sync
/// (`1970-01-01` for a full build). Runs in the background; poll [`status`].
pub fn start(base: &str, after: &str) -> Res {
    if RUNNING.swap(true, Ordering::SeqCst) {
        return Err("a sync is already running".into());
    }
    CANCEL.store(false, Ordering::SeqCst);
    DONE.store(0, Ordering::SeqCst);
    TOTAL.store(0, Ordering::SeqCst);
    BYTES.store(0, Ordering::SeqCst);
    *progress().lock().unwrap() = Progress { phase: "Starting".into(), ..Default::default() };
    let base = base.trim_end_matches('/').to_string();
    let after = if after.is_empty() { "1970-01-01".to_string() } else { after.to_string() };
    std::thread::Builder::new()
        .name("fp-sync".into())
        .spawn(move || {
            // Never block_on inside block_on: run() is plain code that blocks on one step at a time.
            let r = std::panic::catch_unwind(|| run(&base, &after))
                .unwrap_or_else(|_| Err("internal error during sync".into()));
            let mut p = progress().lock().unwrap();
            match r {
                Ok(last) => {
                    p.phase = "Done".into();
                    p.last_update = Some(last);
                }
                Err(e) => {
                    log::warn!("sync failed: {e}");
                    p.phase = "Failed".into();
                    p.error = Some(e);
                }
            }
            RUNNING.store(false, Ordering::SeqCst);
        })
        .map_err(|e| e.to_string())?;
    Ok("{}".into())
}

fn run(base: &str, after: &str) -> Result<String, String> {
    let with = |f: &dyn Fn(&FlashpointArchive) -> Result<(), String>| crate::core::with_db(|fp| f(fp));

    phase("Counting updates");
    let total: serde_json::Value = get_json(&format!("{base}/api/games/updates?after={after}"))?;
    TOTAL.store(total["total"].as_i64().unwrap_or(0), Ordering::SeqCst);

    phase("Tags");
    let raw: RawTags = get_json(&format!("{base}/api/tags?after={after}"))?;
    let tags: Vec<RemoteTag> = raw
        .tags
        .into_iter()
        .map(|t| RemoteTag {
            id: t.id,
            name: t.name,
            description: t.description,
            category: t.category,
            date_modified: t.date_modified,
            aliases: aliases(&t.aliases),
            deleted: t.deleted,
        })
        .collect();
    let cats = raw.categories;
    with(&|fp| {
        rt().block_on(fp.update_apply_categories(cats.clone())).map_err(|e| e.to_string())?;
        rt().block_on(fp.update_apply_tags(tags.clone())).map_err(|e| e.to_string())
    })?;

    phase("Platforms");
    let raw: Vec<RawPlatform> = get_json(&format!("{base}/api/platforms?after={after}"))?;
    let platforms: Vec<RemotePlatform> = raw
        .into_iter()
        .map(|p| RemotePlatform {
            id: p.id,
            name: p.name,
            description: p.description,
            date_modified: p.date_modified,
            aliases: aliases(&p.aliases),
            deleted: p.deleted,
        })
        .collect();
    with(&|fp| rt().block_on(fp.update_apply_platforms(platforms.clone())).map_err(|e| e.to_string()))?;

    phase("Games");
    let before = chrono_now();
    let mut last = after.to_string();
    let mut after_id = String::new();
    loop {
        if CANCEL.load(Ordering::SeqCst) {
            return Err("cancelled".into());
        }
        let url = format!("{base}/api/games?after={after}&before={before}&broad=true&afterId={after_id}");
        let res: RemoteGamesRes = get_json(&url)?;
        let Some(tail) = res.games.last() else { break };
        after_id = tail.id.clone();
        for g in &res.games {
            if later(&g.date_modified, &last) {
                last = g.date_modified.clone();
            }
        }
        let n = res.games.len() as i64;
        with(&|fp| rt().block_on(fp.update_apply_games(&res, OWNER)).map_err(|e| e.to_string()))?;
        DONE.fetch_add(n, Ordering::SeqCst);
    }

    phase("Removed games");
    let deleted: RemoteDeletedGamesRes = get_json(&format!("{base}/api/games/deleted"))?;
    with(&|fp| rt().block_on(fp.update_delete_games(&deleted)).map_err(|e| e.to_string()))?;

    phase("Redirects");
    let raw: Vec<RawRedirect> = get_json(&format!("{base}/api/game-redirects"))?;
    let redirects: Vec<GameRedirect> = raw.into_iter().map(|r| GameRedirect { source_id: r.source_id, dest_id: r.id }).collect();
    with(&|fp| rt().block_on(fp.update_apply_redirects(redirects.clone())).map_err(|e| e.to_string()))?;

    phase("Optimizing");
    with(&|fp| rt().block_on(fp.optimize_database()).map_err(|e| e.to_string()))?;
    Ok(last)
}

fn chrono_now() -> String {
    // ISO 8601 UTC without pulling chrono in directly.
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.000Z", rem / 3600, (rem / 60) % 60, rem % 60)
}

/// Howard Hinnant's days-to-civil algorithm.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    #[test]
    fn dates() {
        assert_eq!(super::civil_from_days(0), (1970, 1, 1));
        assert_eq!(super::civil_from_days(20_414), (2025, 11, 22));
    }
}
