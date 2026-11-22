//! The app's native core, in plain Rust so it can be tested on the host.
//!
//! The database is Flashpoint's own `flashpoint-archive` crate (the library the desktop
//! launcher runs on), so search syntax, filters, sorting and paging behave exactly like the
//! launcher. Everything crosses to Kotlin as JSON strings.

use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock, RwLock};

use flashpoint_archive::game::search::{GameSearchDirection, GameSearchOffset, GameSearchSortable};
use flashpoint_archive::game_data::PartialGameData;
use flashpoint_archive::FlashpointArchive;
use fp_proxy::{Config, GameServer, ProxyHandle};
use serde::Deserialize;
use serde_json::json;

pub type Res = Result<String, String>;

pub(crate) fn rt() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("fp-db")
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

fn db() -> &'static RwLock<Option<FlashpointArchive>> {
    static DB: OnceLock<RwLock<Option<FlashpointArchive>>> = OnceLock::new();
    DB.get_or_init(|| RwLock::new(None))
}

pub(crate) fn with_db<T>(f: impl FnOnce(&FlashpointArchive) -> Result<T, String>) -> Result<T, String> {
    let guard = db().read().map_err(|_| "database lock poisoned".to_string())?;
    let fp = guard.as_ref().ok_or_else(|| "no database loaded".to_string())?;
    f(fp)
}

fn e<E: std::fmt::Display>(err: E) -> String {
    err.to_string()
}

/// Open (and migrate, like the launcher does) a flashpoint.sqlite. `:memory:` for tests.
pub fn open_database(path: &str) -> Res {
    let mut fp = FlashpointArchive::new();
    fp.load_database(path).map_err(e)?;
    let count = rt().block_on(fp.count_games()).map_err(e)?;
    *db().write().map_err(|_| "database lock poisoned")? = Some(fp);
    Ok(json!({ "games": count }).to_string())
}

/// Search options the launcher's browse page sets outside the search box.
#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
pub struct SearchOpts {
    pub installed: Option<bool>,
    /// TITLE, DEVELOPER, PUBLISHER, SERIES, PLATFORM, DATEADDED, DATEMODIFIED, RELEASEDATE,
    /// LASTPLAYED, PLAYTIME, RANDOM, CUSTOM
    pub order: Option<String>,
    pub descending: bool,
    pub platforms: Option<Vec<String>>,
    /// Playlist order (CUSTOM sort) or restriction to a playlist's games.
    pub ids: Option<Vec<String>>,
    pub tag_blacklist: Option<Vec<String>>,
    /// Library: "arcade" (Games tab) or "theatre" (Animations tab).
    pub library: Option<String>,
    /// Only games that have been played (the Home page's Play History).
    pub played: bool,
    /// Keyset offset: the last game of the previous page (`{"value", "title", "id"}`).
    pub after: Option<PageMark>,
}

#[derive(Deserialize, Clone)]
pub struct PageMark {
    pub value: serde_json::Value,
    pub title: String,
    pub id: String,
}

fn sortable(s: Option<&str>) -> GameSearchSortable {
    match s.unwrap_or("TITLE").to_ascii_uppercase().as_str() {
        "DEVELOPER" => GameSearchSortable::DEVELOPER,
        "PUBLISHER" => GameSearchSortable::PUBLISHER,
        "SERIES" => GameSearchSortable::SERIES,
        "PLATFORM" => GameSearchSortable::PLATFORM,
        "DATEADDED" => GameSearchSortable::DATEADDED,
        "DATEMODIFIED" => GameSearchSortable::DATEMODIFIED,
        "RELEASEDATE" => GameSearchSortable::RELEASEDATE,
        "LASTPLAYED" => GameSearchSortable::LASTPLAYED,
        "PLAYTIME" => GameSearchSortable::PLAYTIME,
        "RANDOM" => GameSearchSortable::RANDOM,
        "CUSTOM" => GameSearchSortable::CUSTOM,
        _ => GameSearchSortable::TITLE,
    }
}

fn build_search(fp: &FlashpointArchive, input: &str, opts: &SearchOpts, limit: i64) -> flashpoint_archive::game::search::GameSearch {
    let mut s = fp.parse_user_input(input).search;
    s.limit = limit;
    s.slim = true;
    s.order.column = sortable(opts.order.as_deref());
    s.order.direction = if opts.descending { GameSearchDirection::DESC } else { GameSearchDirection::ASC };
    if let Some(i) = opts.installed {
        s.filter.bool_comp.installed = Some(i);
    }
    if let Some(p) = &opts.platforms {
        if !p.is_empty() {
            s.filter.exact_whitelist.platforms = Some(p.clone());
            s.filter.match_any = s.filter.match_any || p.len() > 1;
        }
    }
    if let Some(ids) = &opts.ids {
        s.filter.exact_whitelist.id = Some(ids.clone());
        if s.order.column == GameSearchSortable::CUSTOM {
            s.custom_id_order = Some(ids.clone());
        }
    }
    if let Some(tb) = &opts.tag_blacklist {
        if !tb.is_empty() {
            s.with_tag_filter = Some(tb.clone());
        }
    }
    if let Some(lib) = opts.library.as_ref().filter(|l| !l.is_empty()) {
        s.filter.exact_whitelist.library = Some(vec![lib.clone()]);
    }
    if opts.played {
        s.filter.higher_than.playcount = Some(0);
    }
    if let Some(a) = &opts.after {
        s.offset = Some(GameSearchOffset { value: a.value.clone(), title: a.title.clone(), game_id: a.id.clone() });
    }
    s
}

/// Total plus the keyset mark that ends every page, so the grid can jump to any page.
pub fn search_index(input: &str, opts_json: &str, page_size: i64) -> Res {
    let opts: SearchOpts = serde_json::from_str(opts_json).unwrap_or_default();
    with_db(|fp| {
        let mut s = build_search(fp, input, &opts, page_size);
        let total = rt().block_on(fp.search_games_total(&s)).map_err(e)?;
        let marks = rt().block_on(fp.search_games_index(&mut s, None)).map_err(e)?;
        let marks: Vec<_> = marks.into_iter().map(|m| json!({ "value": m.order_val, "title": m.title, "id": m.id })).collect();
        Ok(json!({ "total": total, "pageSize": page_size, "marks": marks }).to_string())
    })
}

pub fn search_page(input: &str, opts_json: &str, page_size: i64) -> Res {
    let opts: SearchOpts = serde_json::from_str(opts_json).unwrap_or_default();
    with_db(|fp| {
        let s = build_search(fp, input, &opts, page_size);
        let games = rt().block_on(fp.search_games(&s)).map_err(e)?;
        serde_json::to_string(&games).map_err(e)
    })
}

/// Random picks (the Home page), honouring the same filters as a search.
pub fn random_games(opts_json: &str, count: i64) -> Res {
    let opts: SearchOpts = serde_json::from_str(opts_json).unwrap_or_default();
    with_db(|fp| {
        let s = build_search(fp, "", &opts, count);
        let games = rt().block_on(fp.search_games_random(&s, count)).map_err(e)?;
        serde_json::to_string(&games).map_err(e)
    })
}

/// One game with its data packs and additional apps, for the details panel.
pub fn find_game(id: &str) -> Res {
    with_db(|fp| {
        let Some(mut g) = rt().block_on(fp.find_game(id)).map_err(e)? else {
            return Ok("null".into());
        };
        if g.game_data.is_none() {
            g.game_data = Some(rt().block_on(fp.find_game_data(id)).map_err(e)?);
        }
        serde_json::to_string(&g).map_err(e)
    })
}

pub fn platforms() -> Res {
    with_db(|fp| {
        let p = rt().block_on(fp.find_all_platforms()).map_err(e)?;
        let names: Vec<_> = p.into_iter().map(|t| t.name).collect();
        serde_json::to_string(&names).map_err(e)
    })
}

/// Record a downloaded data pack, like the launcher's import step.
pub fn mark_data_on_disk(game_data_id: i64, path: &str) -> Res {
    with_db(|fp| {
        let Some(gd) = rt().block_on(fp.find_game_data_by_id(game_data_id)).map_err(e)? else {
            return Err(format!("no game data {game_data_id}"));
        };
        let mut p: PartialGameData = gd.into();
        p.present_on_disk = Some(true);
        p.path = Some(path.to_string());
        let saved = rt().block_on(fp.save_game_data(&p)).map_err(e)?;
        serde_json::to_string(&saved).map_err(e)
    })
}

pub fn optimize() -> Res {
    with_db(|fp| rt().block_on(fp.optimize_database()).map(|_| "{}".to_string()).map_err(e))
}

pub fn clear_playtime() -> Res {
    with_db(|fp| rt().block_on(fp.clear_playtime_tracking()).map(|_| "{}".to_string()).map_err(e))
}

pub fn add_playtime(game_id: &str, seconds: i64) -> Res {
    with_db(|fp| {
        rt().block_on(fp.add_game_playtime(game_id, seconds)).map_err(e)?;
        Ok("{}".into())
    })
}

// ---- game server --------------------------------------------------------------------

struct Proxy {
    server: Arc<GameServer>,
    handle: ProxyHandle,
}

fn proxy() -> &'static Mutex<Option<Proxy>> {
    static P: OnceLock<Mutex<Option<Proxy>>> = OnceLock::new();
    P.get_or_init(|| Mutex::new(None))
}

/// Start (or reconfigure) the game server. Returns the port.
pub fn proxy_start(port: u16, htdocs: &str, legacy_base: &str, cache_remote: bool, live_web: bool) -> Result<u16, String> {
    let cfg = Config {
        htdocs: (!htdocs.is_empty()).then(|| htdocs.into()),
        legacy_base: (!legacy_base.is_empty()).then(|| legacy_base.to_string()),
        cache_remote,
        live_web,
    };
    let mut p = proxy().lock().map_err(|_| "proxy lock poisoned")?;
    if let Some(running) = p.as_ref() {
        running.server.set_config(cfg);
        return Ok(running.handle.port);
    }
    let server = GameServer::new(cfg);
    let handle = fp_proxy::start(server.clone(), port).map_err(e)?;
    let port = handle.port;
    *p = Some(Proxy { server, handle });
    Ok(port)
}

fn with_server<T>(f: impl FnOnce(&GameServer) -> Result<T, String>) -> Result<T, String> {
    let p = proxy().lock().map_err(|_| "proxy lock poisoned")?;
    let p = p.as_ref().ok_or("game server not started")?;
    f(&p.server)
}

pub fn proxy_mount(zip: &str) -> Res {
    with_server(|s| s.mount(Path::new(zip)).map(|n| json!({ "files": n }).to_string()).map_err(e))
}

pub fn proxy_unmount_all() {
    let _ = with_server(|s| {
        s.unmount_all();
        Ok(())
    });
}

/// In-process read for native players (Ruffle, ScummVM): no HTTP round trip.
pub fn proxy_read(url: &str) -> Option<Vec<u8>> {
    with_server(|s| Ok(s.read(url))).ok().flatten()
}

#[cfg(test)]
mod tests {
    use super::*;
    use flashpoint_archive::game::PartialGame;

    fn game(id: &str, title: &str, platform: &str) -> PartialGame {
        let mut g: PartialGame = serde_json::from_value(json!({ "id": id })).unwrap();
        g.title = Some(title.into());
        g.primary_platform = Some(platform.into());
        g.platforms = Some(serde_json::from_value(json!(platform)).unwrap());
        g.library = Some("arcade".into());
        g
    }

    #[test]
    fn launcher_search_and_paging() {
        open_database(":memory:").unwrap();
        with_db(|fp| {
            for (i, t) in ["Poptropica", "Super Mario 63", "Alien Hominid", "Age of War", "Antbuster"].iter().enumerate() {
                let p = if i == 1 { "HTML5" } else { "Flash" };
                rt().block_on(fp.create_game(&game(&format!("id-{i}"), t, p))).map_err(e)?;
            }
            Ok(())
        })
        .unwrap();

        let idx: serde_json::Value = serde_json::from_str(&search_index("", "{}", 2).unwrap()).unwrap();
        assert_eq!(idx["total"], 5);

        let page1: serde_json::Value = serde_json::from_str(&search_page("", "{}", 2).unwrap()).unwrap();
        let titles: Vec<_> = page1.as_array().unwrap().iter().map(|g| g["title"].as_str().unwrap().to_string()).collect();
        assert_eq!(titles, ["Age of War", "Alien Hominid"]);

        // Next page from the index mark, exactly as the grid will do it.
        let mark = &idx["marks"][0];
        let opts = json!({ "after": { "value": mark["value"], "title": mark["title"], "id": mark["id"] } }).to_string();
        let page2: serde_json::Value = serde_json::from_str(&search_page("", &opts, 2).unwrap()).unwrap();
        assert_eq!(page2[0]["title"], "Antbuster");

        // Launcher search syntax and the platform filter.
        let r: serde_json::Value = serde_json::from_str(&search_page("mario", "{}", 50).unwrap()).unwrap();
        assert_eq!(r.as_array().unwrap().len(), 1);
        let r: serde_json::Value = serde_json::from_str(&search_page("", r#"{"platforms":["HTML5"]}"#, 50).unwrap()).unwrap();
        assert_eq!(r[0]["title"], "Super Mario 63");

        let r: serde_json::Value = serde_json::from_str(&random_games(r#"{"library":"arcade"}"#, 3).unwrap()).unwrap();
        assert_eq!(r.as_array().unwrap().len(), 3);
        let r: serde_json::Value = serde_json::from_str(&search_page("", r#"{"library":"theatre"}"#, 50).unwrap()).unwrap();
        assert_eq!(r.as_array().unwrap().len(), 0);

        let g: serde_json::Value = serde_json::from_str(&find_game("id-0").unwrap()).unwrap();
        assert_eq!(g["title"], "Poptropica");
    }
}
