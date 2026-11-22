//! Calls into `com.armsx.flashpoint.game.RuffleActivity`.
//! Based on ruffle-android's java.rs (MIT OR Apache-2.0): method ids looked up once in nativeInit.

use jni::objects::{JClass, JIntArray, JMethodID, JObject, JString, ReleaseMode};
use jni::signature::{Primitive, ReturnType};
use jni::{jni_sig, jni_str, Env};
use std::path::PathBuf;
use std::sync::OnceLock;

pub struct JavaInterface {
    get_surface_width: JMethodID,
    get_surface_height: JMethodID,
    get_loc_in_window: JMethodID,
    get_string: JMethodID,
}

static JAVA_INTERFACE: OnceLock<JavaInterface> = OnceLock::new();

impl JavaInterface {
    fn int(env: &mut Env, this: &JObject, id: JMethodID) -> i32 {
        unsafe { env.call_method_unchecked(this, id, ReturnType::Primitive(Primitive::Int), &[]) }
            .expect("RuffleActivity getters never throw")
            .i()
            .unwrap()
    }

    pub fn get_surface_width(env: &mut Env, this: &JObject) -> i32 {
        Self::int(env, this, Self::get().get_surface_width)
    }

    pub fn get_surface_height(env: &mut Env, this: &JObject) -> i32 {
        Self::int(env, this, Self::get().get_surface_height)
    }

    pub fn get_loc_in_window(env: &mut Env, this: &JObject) -> (i32, i32) {
        let object = unsafe { env.call_method_unchecked(this, Self::get().get_loc_in_window, ReturnType::Array, &[]) }
            .expect("getLocInWindow() never throws")
            .l()
            .unwrap();
        let arr = unsafe { JIntArray::from_raw(env, object.into_raw()) };
        let elements = unsafe { arr.get_elements(env, ReleaseMode::NoCopyBack).unwrap() };
        let data = unsafe { std::slice::from_raw_parts(elements.as_ptr(), elements.len()) };
        (data[0], data[1])
    }

    /// `getString(key)`: swfUrl, proxyUrl, backend, storageDir, traceOutput. Empty when unset.
    pub fn get_string(env: &mut Env, this: &JObject, key: &str) -> String {
        let jkey = env.new_string(key).unwrap();
        let object = unsafe {
            env.call_method_unchecked(this, Self::get().get_string, ReturnType::Object, &[jni::objects::JValue::Object(jkey.as_ref()).as_jni()])
        }
        .expect("getString() never throws")
        .l()
        .unwrap();
        if object.is_null() {
            return String::new();
        }
        let s = unsafe { env.as_cast_unchecked::<JString>(&object) };
        s.try_to_string(env).unwrap_or_default()
    }

    pub fn storage_dir(env: &mut Env, this: &JObject) -> PathBuf {
        PathBuf::from(Self::get_string(env, this, "storageDir"))
    }

    pub fn get() -> &'static JavaInterface {
        JAVA_INTERFACE.get().expect("nativeInit() must run first")
    }

    pub fn init(env: &mut Env, class: &JClass) {
        let _ = JAVA_INTERFACE.set(JavaInterface {
            get_surface_width: env.get_method_id(class, jni_str!("getSurfaceWidth"), jni_sig!("()I")).expect("getSurfaceWidth"),
            get_surface_height: env.get_method_id(class, jni_str!("getSurfaceHeight"), jni_sig!("()I")).expect("getSurfaceHeight"),
            get_loc_in_window: env.get_method_id(class, jni_str!("getLocInWindow"), jni_sig!("()[I")).expect("getLocInWindow"),
            get_string: env
                .get_method_id(class, jni_str!("getString"), jni_sig!("(Ljava/lang/String;)Ljava/lang/String;"))
                .expect("getString"),
        });
    }
}
