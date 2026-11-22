//! libfpcore.so: JNI entry points for `com.armsx.flashpoint.core.FpCore`.
//!
//! Every call returns JSON. Errors come back as `{"error": "..."}` so Kotlin never has to
//! deal with a Java exception thrown from native code, and panics never cross the boundary.

pub mod core;
pub mod sync;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;

fn arg(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn out(env: &mut JNIEnv, r: core::Res) -> jstring {
    let s = match r {
        Ok(s) => s,
        Err(err) => serde_json::json!({ "error": err }).to_string(),
    };
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn guarded(f: impl FnOnce() -> core::Res) -> core::Res {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|p| {
        let msg = p
            .downcast_ref::<String>()
            .cloned()
            .or_else(|| p.downcast_ref::<&str>().map(|s| s.to_string()))
            .unwrap_or_else(|| "native panic".into());
        Err(msg)
    })
}

macro_rules! jfn {
    ($name:ident ( $($a:ident : JString),* ) $(, $extra:ident : $ty:ty)* => $body:expr) => {
        #[no_mangle]
        pub extern "system" fn $name<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, $($a: JString<'l>,)* $($extra: $ty),*) -> jstring {
            $(let $a = arg(&mut env, &$a);)*
            let r = guarded(|| $body);
            out(&mut env, r)
        }
    };
}

#[no_mangle]
pub extern "system" fn Java_com_armsx_flashpoint_core_FpCore_init(_env: JNIEnv, _c: JClass) {
    #[cfg(target_os = "android")]
    android_logger::init_once(android_logger::Config::default().with_tag("fpcore").with_max_level(log::LevelFilter::Info));
}

jfn!(Java_com_armsx_flashpoint_core_FpCore_openDatabase(path: JString) => core::open_database(&path));
jfn!(Java_com_armsx_flashpoint_core_FpCore_searchIndex(input: JString, opts: JString), page_size: jint
    => core::search_index(&input, &opts, page_size as i64));
jfn!(Java_com_armsx_flashpoint_core_FpCore_searchPage(input: JString, opts: JString), page_size: jint
    => core::search_page(&input, &opts, page_size as i64));
jfn!(Java_com_armsx_flashpoint_core_FpCore_findGame(id: JString) => core::find_game(&id));
jfn!(Java_com_armsx_flashpoint_core_FpCore_platforms() => core::platforms());
jfn!(Java_com_armsx_flashpoint_core_FpCore_markDataOnDisk(path: JString), game_data_id: jlong
    => core::mark_data_on_disk(game_data_id, &path));
jfn!(Java_com_armsx_flashpoint_core_FpCore_addPlaytime(id: JString), seconds: jlong
    => core::add_playtime(&id, seconds));
jfn!(Java_com_armsx_flashpoint_core_FpCore_proxyStart(htdocs: JString, legacy: JString), port: jint, cache: jboolean
    => core::proxy_start(port as u16, &htdocs, &legacy, cache != 0).map(|p| serde_json::json!({ "port": p }).to_string()));
jfn!(Java_com_armsx_flashpoint_core_FpCore_proxyMount(zip: JString) => core::proxy_mount(&zip));
jfn!(Java_com_armsx_flashpoint_core_FpCore_proxyUnmountAll() => { core::proxy_unmount_all(); Ok("{}".into()) });
jfn!(Java_com_armsx_flashpoint_core_FpCore_syncStart(base: JString, after: JString) => sync::start(&base, &after));
jfn!(Java_com_armsx_flashpoint_core_FpCore_syncStatus() => sync::status());
jfn!(Java_com_armsx_flashpoint_core_FpCore_syncCancel() => { sync::cancel(); Ok("{}".into()) });
jfn!(Java_com_armsx_flashpoint_core_FpCore_randomGames(opts: JString), count: jint => core::random_games(&opts, count as i64));
jfn!(Java_com_armsx_flashpoint_core_FpCore_optimize() => core::optimize());
jfn!(Java_com_armsx_flashpoint_core_FpCore_clearPlaytime() => core::clear_playtime());
