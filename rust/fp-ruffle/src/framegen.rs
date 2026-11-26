//! Frame generation (LSFG) for native Flash, through `libarmflash_lsfg.so`
//! (android/app/src/main/cpp/lsfg, ARMSX2's frame generation behind a C ABI, see its
//! `armflash_lsfg.h`). Only the GitHub build has the library, so it is opened at run time and
//! every call here is a no-op without it.
//!
//! It runs on Ruffle's own wgpu device: before that device is made, [`prepare_device`] asks
//! wgpu-hal (vendored, `ARMFLASH_FRAME_GENERATION_WANTED`) for the features its shaders need;
//! afterwards [`attach`] hands the library the raw Vulkan handles. While it is on, the library
//! owns the window's swap chain and [`crate::shader::ShaderTarget`] gives it each frame.

use std::ffi::{c_char, c_void, CString};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::OnceLock;

use ash::vk::Handle;

const ABI: u32 = 1;

#[repr(C)]
struct Device {
    abi: u32,
    queue_family: u32,
    get_instance_proc_addr: *mut c_void,
    instance: u64,
    physical_device: u64,
    device: u64,
    queue: u64,
    enabled_features: u32,
}

#[repr(C)]
struct RawSettings {
    abi: u32,
    enabled: u32,
    multiplier: u32,
    flow_scale: u32,
    fp16: u32,
    target_rate: u32,
    dll_path: *const c_char,
    cache_dir: *const c_char,
}

struct Api {
    _lib: libloading::Library,
    attach: unsafe extern "C" fn(*const Device) -> i32,
    configure: unsafe extern "C" fn(*const RawSettings),
    reason: unsafe extern "C" fn() -> i32,
    set_window: unsafe extern "C" fn(*mut c_void) -> i32,
    release_window: unsafe extern "C" fn(),
    present: unsafe extern "C" fn(u64, u32, u32, u32, f32, f32) -> i32,
    idle: unsafe extern "C" fn(),
    status: unsafe extern "C" fn(*mut c_char, u32) -> u32,
}

// The library's functions are plain C and thread-safe where the header says "any thread"; the
// rest are only called from the render thread.
unsafe impl Send for Api {}
unsafe impl Sync for Api {}

fn api() -> Option<&'static Api> {
    static API: OnceLock<Option<Api>> = OnceLock::new();
    API.get_or_init(|| unsafe {
        let lib = match libloading::Library::new("libarmflash_lsfg.so") {
            Ok(l) => l,
            Err(_) => {
                log::info!("frame generation: not in this build");
                return None;
            }
        };
        let version: libloading::Symbol<unsafe extern "C" fn() -> u32> = lib.get(b"armflash_lsfg_abi_version\0").ok()?;
        if version() != ABI {
            log::warn!("frame generation: library ABI {} (want {ABI}), not used", version());
            return None;
        }
        macro_rules! sym {
            ($name:literal, $ty:ty) => {
                *lib.get::<$ty>(concat!($name, "\0").as_bytes()).ok()?
            };
        }
        Some(Api {
            attach: sym!("armflash_lsfg_attach", unsafe extern "C" fn(*const Device) -> i32),
            configure: sym!("armflash_lsfg_configure", unsafe extern "C" fn(*const RawSettings)),
            reason: sym!("armflash_lsfg_unavailable_reason", unsafe extern "C" fn() -> i32),
            set_window: sym!("armflash_lsfg_set_window", unsafe extern "C" fn(*mut c_void) -> i32),
            release_window: sym!("armflash_lsfg_release_window", unsafe extern "C" fn()),
            present: sym!("armflash_lsfg_present", unsafe extern "C" fn(u64, u32, u32, u32, f32, f32) -> i32),
            idle: sym!("armflash_lsfg_idle", unsafe extern "C" fn()),
            status: sym!("armflash_lsfg_status", unsafe extern "C" fn(*mut c_char, u32) -> u32),
            _lib: lib,
        })
    })
    .as_ref()
}

/// Why frame generation cannot run (the library's ARMFLASH_LSFG_* numbers): 1 not in this
/// build, 2 not on Vulkan, 0 when it can.
pub fn reason() -> i32 {
    match api() {
        None => 1,
        Some(a) if ATTACHED.load(Ordering::Relaxed) != 0 => unsafe { (a.reason)() },
        Some(_) => 2,
    }
}

/// The performance overlay's line ("" while switched off).
pub fn status() -> String {
    let Some(a) = api() else { return String::new() };
    let mut buf = [0u8; 160];
    let n = unsafe { (a.status)(buf.as_mut_ptr().cast(), buf.len() as u32) } as usize;
    String::from_utf8_lossy(&buf[..n.min(buf.len() - 1)]).into_owned()
}

static ATTACHED: AtomicU32 = AtomicU32::new(0);

/// Before Ruffle's device is made: ask wgpu-hal for what frame generation's shaders need
/// (the Vulkan memory model, null descriptors, and shaderFloat16 for Half Precision). Only
/// in a build that has frame generation, so the Play build's device is exactly as before.
pub fn prepare_device(fp16: bool) {
    let wanted = if api().is_some() { 1 | if fp16 { 2 } else { 0 } } else { 0 };
    wgpu_hal::vulkan::ARMFLASH_FRAME_GENERATION_WANTED.store(wanted, Ordering::Relaxed);
    wgpu_hal::vulkan::ARMFLASH_FRAME_GENERATION_ENABLED.store(0, Ordering::Relaxed);
}

/// After it is made: what it was made with (ARMFLASH_LSFG_FEATURE_* bits). Clears the request.
pub fn device_made() -> u32 {
    wgpu_hal::vulkan::ARMFLASH_FRAME_GENERATION_WANTED.store(0, Ordering::Relaxed);
    wgpu_hal::vulkan::ARMFLASH_FRAME_GENERATION_ENABLED.load(Ordering::Relaxed)
}

/// Hand Ruffle's Vulkan device to the library. False on OpenGL ES or without the library.
pub fn attach(device: &wgpu::Device, features: u32) -> bool {
    let Some(a) = api() else { return false };
    let Some(hal) = (unsafe { device.as_hal::<wgpu_hal::api::Vulkan>() }) else { return false };
    let shared = hal.shared_instance();
    let gipa = shared.entry().static_fn().get_instance_proc_addr;
    let d = Device {
        abi: ABI,
        queue_family: hal.queue_family_index(),
        get_instance_proc_addr: gipa as *mut c_void,
        instance: shared.raw_instance().handle().as_raw(),
        physical_device: hal.raw_physical_device().as_raw(),
        device: hal.raw_device().handle().as_raw(),
        queue: hal.raw_queue().as_raw(),
        enabled_features: features,
    };
    let r = unsafe { (a.attach)(&d) };
    if r != 0 {
        log::warn!("frame generation: attach failed ({r})");
        return false;
    }
    ATTACHED.store(1, Ordering::Relaxed);
    true
}

/// Frame generation's settings, from the app (RuffleActivity's "frameGen": key=value lines).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Settings {
    pub enabled: bool,
    pub multiplier: u32,
    pub motion: u32,
    pub fp16: bool,
    pub adaptive: bool,
    pub dll: String,
    pub cache: String,
}

impl Settings {
    pub fn parse(text: &str) -> Self {
        let mut s = Settings { multiplier: 2, motion: 100, ..Default::default() };
        for line in text.lines() {
            let Some((k, v)) = line.split_once('=') else { continue };
            match k.trim() {
                "enabled" => s.enabled = v.trim() == "1",
                "multiplier" => s.multiplier = v.trim().parse().unwrap_or(2),
                "motion" => s.motion = v.trim().parse().unwrap_or(100),
                "fp16" => s.fp16 = v.trim() == "1",
                "adaptive" => s.adaptive = v.trim() == "1",
                "dll" => s.dll = v.trim().to_string(),
                "cache" => s.cache = v.trim().to_string(),
                _ => {}
            }
        }
        s
    }
}

/// The settings, with the screen's refresh rate as adaptive pacing's target.
pub fn configure(s: &Settings, display_rate: f32) {
    let Some(a) = api() else { return };
    let dll = CString::new(s.dll.as_str()).unwrap_or_default();
    let cache = CString::new(s.cache.as_str()).unwrap_or_default();
    let raw = RawSettings {
        abi: ABI,
        enabled: s.enabled as u32,
        multiplier: s.multiplier.clamp(2, 4),
        flow_scale: s.motion.clamp(25, 100),
        fp16: s.fp16 as u32,
        target_rate: if s.adaptive { display_rate.round().max(0.0) as u32 } else { 0 },
        dll_path: dll.as_ptr(),
        cache_dir: cache.as_ptr(),
    };
    unsafe { (a.configure)(&raw) };
}

/// Present on [window] instead of wgpu: true when the library took it.
pub fn set_window(window: &ndk::native_window::NativeWindow) -> bool {
    let Some(a) = api() else { return false };
    unsafe { (a.set_window)(window.ptr().as_ptr().cast()) == 0 }
}

pub fn release_window() {
    if let Some(a) = api() {
        unsafe { (a.release_window)() }
    }
}

/// Wait until nothing submitted reads a frame image, before one is destroyed.
pub fn idle() {
    if let Some(a) = api() {
        unsafe { (a.idle)() }
    }
}

/// Present [texture] (Ruffle's frame, rendered by work already submitted): 1 presented, 0 not
/// this time, negative when the device or window is gone.
pub fn present(texture: &wgpu::Texture, content_rate: f32, display_rate: f32) -> i32 {
    let Some(a) = api() else { return -1 };
    let Some(raw) = (unsafe { texture.as_hal::<wgpu_hal::api::Vulkan>() }).map(|t| unsafe { t.raw_handle() }.as_raw()) else {
        return -1;
    };
    let format = match texture.format() {
        wgpu::TextureFormat::Bgra8Unorm => 44, // VK_FORMAT_B8G8R8A8_UNORM
        _ => 37,                               // VK_FORMAT_R8G8B8A8_UNORM
    };
    unsafe { (a.present)(raw, texture.width(), texture.height(), format, content_rate, display_rate) }
}

static CONTENT_RATE: AtomicU32 = AtomicU32::new(0);
static DISPLAY_RATE: AtomicU32 = AtomicU32::new(0);

/// The game's frame rate (the player loop, every frame).
pub fn set_content_rate(hz: f32) {
    CONTENT_RATE.store(hz.to_bits(), Ordering::Relaxed);
}

pub fn content_rate() -> f32 {
    f32::from_bits(CONTENT_RATE.load(Ordering::Relaxed))
}

/// The screen's refresh rate (the app, every couple of seconds and with each settings change).
pub fn set_display_rate(hz: f32) {
    if hz.is_finite() && hz > 0.0 {
        DISPLAY_RATE.store(hz.to_bits(), Ordering::Relaxed);
    }
}

/// The screen's refresh rate, 60 until the app has said.
pub fn display_rate() -> f32 {
    let hz = f32::from_bits(DISPLAY_RATE.load(Ordering::Relaxed));
    if hz > 0.0 { hz } else { 60.0 }
}

/// The "display=" line of the app's frameGen settings.
pub fn set_display_rate_from(text: &str) {
    if let Some(hz) = text.lines().find_map(|l| l.strip_prefix("display=")).and_then(|v| v.trim().parse::<f32>().ok()) {
        set_display_rate(hz);
    }
}
