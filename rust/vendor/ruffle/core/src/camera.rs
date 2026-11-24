//! The device camera, for `flash.media.Camera` (AS3) and `Camera` (AS1/2).
//!
//! ARMFlash addition: Ruffle has no camera of its own, so webcam games were told there was none.
//! A frontend that has a camera gives the player one through [`set_backend`]; the AVM classes,
//! `Video.attachCamera` / `Video.attachVideo(camera)` and drawing a camera video into a
//! `BitmapData` all read frames through it. Without a backend, there is no camera, as before.

use std::sync::{Arc, OnceLock};

/// One captured frame: unpremultiplied RGBA (fully opaque), already turned upright.
#[derive(Clone)]
pub struct CameraFrame {
    pub width: u32,
    pub height: u32,
    pub rgba: Arc<Vec<u8>>,
    /// Counts up with every new frame, so a video uploads each frame once.
    pub seq: u64,
}

/// Whether the person let the game use the camera.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CameraPermission {
    /// Not answered yet (the system's question is showing).
    Asking,
    Allowed,
    Refused,
}

pub trait CameraBackend: Send + Sync {
    /// The cameras, by the names Flash shows (`Camera.names`); empty when there are none.
    fn names(&self) -> Vec<String>;

    /// Start (or change) capture on camera `index` at about this size and rate. Asking for the
    /// person's permission, when needed, is the backend's job.
    fn start(&self, index: usize, width: u32, height: u32, fps: f64);

    fn permission(&self) -> CameraPermission;

    /// The newest frame, if one has arrived.
    fn frame(&self) -> Option<CameraFrame>;

    /// How much the picture is changing, from 0 (still) to 100 (a lot), as Flash's
    /// `activityLevel` reports it.
    fn activity_level(&self) -> f64;

    /// Frames a second actually arriving.
    fn current_fps(&self) -> f64;
}

static BACKEND: OnceLock<Box<dyn CameraBackend>> = OnceLock::new();

/// Give the player a camera. Only the first call counts (one per process).
pub fn set_backend(backend: Box<dyn CameraBackend>) {
    let _ = BACKEND.set(backend);
}

pub fn backend() -> Option<&'static dyn CameraBackend> {
    BACKEND.get().map(|b| b.as_ref())
}

/// The camera names, empty without a camera.
pub fn names() -> Vec<String> {
    backend().map(|b| b.names()).unwrap_or_default()
}

/// The newest frame, if there is a camera and it has delivered one.
pub fn frame() -> Option<CameraFrame> {
    backend().and_then(|b| b.frame())
}
