//! The device camera for Flash webcam games (`ruffle_core::camera`), fed by RuffleActivity.
//!
//! RuffleActivity captures with Camera2 and hands each frame's Y, U and V planes over JNI
//! (`nativeCameraFrame`); they become upright RGBA here, along with Flash's activity level (how
//! much the picture changes) and the frame rate. A game asking for the camera calls
//! RuffleActivity's `startCamera`, which asks the person first; the answer comes back through
//! `nativeCameraPermission`.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use jni::objects::{JByteBuffer, JObject, JValue};
use jni::sys::{jint, jobject};
use jni::{jni_sig, jni_str, EnvUnowned, JavaVM};
use jni::errors::ThrowRuntimeExAndDefault;
use ruffle_core::camera::{CameraBackend, CameraFrame, CameraPermission};

/// 1 allowed, 0 asking (or not asked yet), -1 refused.
static PERMISSION: AtomicI32 = AtomicI32::new(0);
static SEQ: AtomicU64 = AtomicU64::new(0);
static STATE: Mutex<State> = Mutex::new(State { frame: None, previous: Vec::new(), activity: 0.0, arrivals: VecDeque::new() });

struct State {
    frame: Option<CameraFrame>,
    /// The last frame's brightness on a coarse grid, to measure change against.
    previous: Vec<u8>,
    activity: f64,
    /// When the last few frames arrived, for the frame rate.
    arrivals: VecDeque<Instant>,
}

/// The coarse grid activity is measured on.
const GRID_W: usize = 32;
const GRID_H: usize = 24;

pub struct AndroidCamera {
    vm: usize,
    activity: usize,
    names: Vec<String>,
}

impl AndroidCamera {
    /// [names]: the cameras, one per line, as RuffleActivity lists them (front first).
    pub fn new(vm: *mut jni::sys::JavaVM, activity: jobject, names: &str) -> Self {
        Self {
            vm: vm as usize,
            activity: activity as usize,
            names: names.lines().map(str::to_string).filter(|n| !n.is_empty()).collect(),
        }
    }
}

impl CameraBackend for AndroidCamera {
    fn names(&self) -> Vec<String> {
        self.names.clone()
    }

    fn start(&self, index: usize, width: u32, height: u32, fps: f64) {
        let vm = unsafe { JavaVM::from_raw(self.vm as *mut jni::sys::JavaVM) };
        let activity = self.activity as jobject;
        let result = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
            let activity = unsafe { env.as_cast_raw::<JObject>(&activity) }?;
            env.call_method(
                &*activity,
                jni_str!("startCamera"),
                jni_sig!("(IIII)V"),
                &[JValue::Int(index as i32), JValue::Int(width as i32), JValue::Int(height as i32), JValue::Int(fps.round() as i32)],
            )?;
            Ok(())
        });
        if let Err(e) = result {
            log::warn!("camera: start failed: {e}");
        }
    }

    fn permission(&self) -> CameraPermission {
        match PERMISSION.load(Ordering::Acquire) {
            1 => CameraPermission::Allowed,
            -1 => CameraPermission::Refused,
            _ => CameraPermission::Asking,
        }
    }

    fn frame(&self) -> Option<CameraFrame> {
        STATE.lock().ok()?.frame.clone()
    }

    fn activity_level(&self) -> f64 {
        STATE.lock().map(|s| if s.frame.is_some() { s.activity } else { -1.0 }).unwrap_or(-1.0)
    }

    fn current_fps(&self) -> f64 {
        let Ok(s) = STATE.lock() else { return 0.0 };
        match (s.arrivals.front(), s.arrivals.back()) {
            (Some(first), Some(last)) if s.arrivals.len() > 1 => {
                let secs = last.duration_since(*first).as_secs_f64();
                if secs > 0.0 { (s.arrivals.len() - 1) as f64 / secs } else { 0.0 }
            }
            _ => 0.0,
        }
    }
}

/// One YUV 4:2:0 frame (Android's YUV_420_888) as upright, opaque RGBA. [rotation] (0, 90, 180
/// or 270) is how far clockwise the picture has to turn to stand up.
#[allow(clippy::too_many_arguments)]
fn to_rgba(y: &[u8], u: &[u8], v: &[u8], w: usize, h: usize, y_stride: usize, uv_stride: usize, uv_step: usize, rotation: i32) -> (Vec<u8>, usize, usize) {
    let (ow, oh) = if rotation == 90 || rotation == 270 { (h, w) } else { (w, h) };
    let mut out = vec![255u8; ow * oh * 4];
    for oy in 0..oh {
        for ox in 0..ow {
            // The source pixel this output pixel shows.
            let (sx, sy) = match rotation {
                90 => (oy, h - 1 - ox),
                180 => (w - 1 - ox, h - 1 - oy),
                270 => (w - 1 - oy, ox),
                _ => (ox, oy),
            };
            let yv = *y.get(sy * y_stride + sx).unwrap_or(&0) as i32;
            let uvi = (sy / 2) * uv_stride + (sx / 2) * uv_step;
            let uu = *u.get(uvi).unwrap_or(&128) as i32 - 128;
            let vv = *v.get(uvi).unwrap_or(&128) as i32 - 128;
            // Full-range BT.601, the camera's usual JPEG range.
            let r = yv + ((91881 * vv) >> 16);
            let g = yv - ((22554 * uu + 46802 * vv) >> 16);
            let b = yv + ((116130 * uu) >> 16);
            let o = (oy * ow + ox) * 4;
            out[o] = r.clamp(0, 255) as u8;
            out[o + 1] = g.clamp(0, 255) as u8;
            out[o + 2] = b.clamp(0, 255) as u8;
        }
    }
    (out, ow, oh)
}

/// Brightness on the coarse grid (orientation does not matter for change).
fn grid(y: &[u8], w: usize, h: usize, y_stride: usize) -> Vec<u8> {
    let mut g = Vec::with_capacity(GRID_W * GRID_H);
    for gy in 0..GRID_H {
        for gx in 0..GRID_W {
            let sx = (gx * 2 + 1) * w / (GRID_W * 2);
            let sy = (gy * 2 + 1) * h / (GRID_H * 2);
            g.push(*y.get(sy * y_stride + sx).unwrap_or(&0));
        }
    }
    g
}

/// A frame from RuffleActivity's camera.
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeCameraFrame<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    y: JByteBuffer<'local>,
    u: JByteBuffer<'local>,
    v: JByteBuffer<'local>,
    width: jint,
    height: jint,
    y_stride: jint,
    uv_stride: jint,
    uv_step: jint,
    rotation: jint,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let plane = |buf: &JByteBuffer| -> jni::errors::Result<&[u8]> {
            let ptr = env.get_direct_buffer_address(buf)?;
            let len = env.get_direct_buffer_capacity(buf)?;
            Ok(unsafe { std::slice::from_raw_parts(ptr, len) })
        };
        let (yp, up, vp) = (plane(&y)?, plane(&u)?, plane(&v)?);
        let (w, h) = (width.max(1) as usize, height.max(1) as usize);
        let (rgba, ow, oh) = to_rgba(yp, up, vp, w, h, y_stride as usize, uv_stride as usize, uv_step.max(1) as usize, rotation);
        let now_grid = grid(yp, w, h, y_stride as usize);
        if let Ok(mut s) = STATE.lock() {
            // Activity: the mean change in brightness across the grid, less the sensor's own
            // noise, scaled so a hand waving across part of the picture reads about 50.
            if s.previous.len() == now_grid.len() {
                let diff: u32 = s.previous.iter().zip(&now_grid).map(|(a, b)| (*a as i32 - *b as i32).unsigned_abs()).sum();
                let mean = diff as f64 / now_grid.len() as f64;
                s.activity = ((mean - 1.5).max(0.0) * 5.0).min(100.0);
            }
            s.previous = now_grid;
            let now = Instant::now();
            s.arrivals.push_back(now);
            while s.arrivals.len() > 30 {
                s.arrivals.pop_front();
            }
            s.frame = Some(CameraFrame {
                width: ow as u32,
                height: oh as u32,
                rgba: Arc::new(rgba),
                seq: SEQ.fetch_add(1, Ordering::AcqRel) + 1,
            });
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// The person's answer: 1 allowed, -1 refused (0 while asking).
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeCameraPermission<'local>(
    _env: EnvUnowned<'local>,
    _this: JObject<'local>,
    state: jint,
) {
    PERMISSION.store(state.clamp(-1, 1), Ordering::Release);
}
