//! RetroArch (.slangp) shaders on native Flash, through librashader's wgpu runtime, and frame
//! generation (framegen.rs).
//!
//! [`ShaderTarget`] is Ruffle's swap-chain target with two differences: when a shader preset is
//! loaded, Ruffle draws each frame into an offscreen texture and the preset's filter chain
//! draws that onto the window; and while frame generation runs, wgpu's surface is let go, Ruffle
//! (or the chain) draws into frame generation's input texture, and frame generation's library
//! presents it and the frames it makes between it and the last one. Without either, Ruffle draws
//! straight into the window, exactly as `SwapChainTarget` does. The surface sits behind a lock so
//! a new window (after the app was in the background) can be attached through the backend's
//! shared `target()`.

use crate::framegen;
use librashader_common::Size;
use librashader_runtime::parameters::FilterChainParameters;
use librashader_runtime_wgpu::{FilterChainWgpu, WgpuOutputView};
use ruffle_render_wgpu::backend::{create_wgpu_instance, request_adapter_and_device, WgpuRenderBackend};
use ruffle_render_wgpu::descriptors::Descriptors;
use ruffle_render_wgpu::target::{RenderTarget, RenderTargetFrame};
use ruffle_render_wgpu::utils::remove_srgb;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

pub struct ShaderTarget {
    /// None while the app has no window (in the background), and while frame generation owns
    /// the window: the surface is let go with its window, as Vulkan allows one surface per
    /// Android window.
    surface: Mutex<Option<wgpu::Surface<'static>>>,
    config: Mutex<wgpu::SurfaceConfiguration>,
    chain: Mutex<Option<FilterChainWgpu>>,
    /// Ruffle's frame when a chain runs: same size and format as the window.
    offscreen: Mutex<Option<(wgpu::Texture, wgpu::TextureView)>>,
    frames: AtomicUsize,
    fg: Mutex<FrameGen>,
}

/// Frame generation's side of the target.
#[derive(Default)]
struct FrameGen {
    /// Its library has Ruffle's device (Vulkan, and a build that has it).
    attached: bool,
    /// The library owns the window's swap chain and presents every frame.
    active: bool,
    settings: framegen::Settings,
    window: Option<ndk::native_window::NativeWindow>,
    /// The window would not take the library's surface: not tried again until the window or the
    /// settings change.
    refused: bool,
    /// The device or window went away under it: wgpu presents again.
    broken: bool,
    /// Where Ruffle (or the chain) draws while it runs: the window's size, Ruffle's format.
    input: Option<(wgpu::Texture, wgpu::TextureView)>,
}

impl std::fmt::Debug for ShaderTarget {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ShaderTarget").finish_non_exhaustive()
    }
}

#[derive(Debug)]
pub struct ShaderFrame {
    /// The window's image; None while frame generation presents.
    surface: Option<wgpu::SurfaceTexture>,
    /// What ends up on screen: the window's image, or frame generation's input.
    surface_view: wgpu::TextureView,
    /// Where Ruffle draws: the offscreen texture with a chain, else `surface_view`.
    view: wgpu::TextureView,
    chained: bool,
}

impl RenderTargetFrame for ShaderFrame {
    fn into_view(self) -> wgpu::TextureView {
        self.view
    }
    fn view(&self) -> &wgpu::TextureView {
        &self.view
    }
}

fn surface_config(surface: &wgpu::Surface, adapter: &wgpu::Adapter, (width, height): (u32, u32)) -> wgpu::SurfaceConfiguration {
    // As Ruffle's SwapChainTarget: a non-sRGB format, viewed linear.
    let caps = surface.get_capabilities(adapter);
    let format = caps
        .formats
        .iter()
        .find(|f| matches!(f, wgpu::TextureFormat::Rgba8Unorm | wgpu::TextureFormat::Bgra8Unorm))
        .or_else(|| caps.formats.first())
        .copied()
        .unwrap_or(wgpu::TextureFormat::Rgba8Unorm);
    let linear = remove_srgb(format);
    wgpu::SurfaceConfiguration {
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
        format,
        color_space: wgpu::SurfaceColorSpace::Auto,
        width,
        height,
        present_mode: wgpu::PresentMode::Fifo,
        desired_maximum_frame_latency: 2,
        alpha_mode: caps.alpha_modes[0],
        view_formats: if linear == format { vec![format] } else { vec![format, linear] },
    }
}

impl ShaderTarget {
    pub fn new(surface: wgpu::Surface<'static>, adapter: &wgpu::Adapter, size: (u32, u32), device: &wgpu::Device) -> Self {
        let config = surface_config(&surface, adapter, size);
        surface.configure(device, &config);
        Self {
            surface: Mutex::new(Some(surface)),
            config: Mutex::new(config),
            chain: Mutex::new(None),
            offscreen: Mutex::new(None),
            frames: AtomicUsize::new(0),
            fg: Mutex::new(FrameGen::default()),
        }
    }

    /// Let the window's surface go: the window is going away (the app left the screen). A second
    /// surface on the same window fails (VK_ERROR_NATIVE_WINDOW_IN_USE_KHR) and wgpu panics on it.
    pub fn release(&self) {
        {
            let mut fg = self.fg.lock().unwrap();
            if fg.active {
                // Waits for the GPU, so the input texture below is no longer read.
                framegen::release_window();
            }
            fg.window = None;
            fg.input = None;
        }
        *self.surface.lock().unwrap() = None;
        *self.offscreen.lock().unwrap() = None;
    }

    /// A new window (the old one, if still here, is let go first). Frame generation takes it
    /// when it was running; otherwise, or when the window will not have it, wgpu does.
    pub fn recreate(&self, d: &Descriptors, window: &ndk::native_window::NativeWindow, size: (u32, u32)) -> Result<(), String> {
        self.release();
        {
            let mut fg = self.fg.lock().unwrap();
            fg.window = Some(window.clone());
            fg.refused = false;
            fg.broken = false;
            if fg.active {
                if framegen::set_window(window) {
                    drop(fg);
                    let mut config = self.config.lock().unwrap();
                    config.width = size.0;
                    config.height = size.1;
                    return Ok(());
                }
                log::warn!("frame generation: the new window would not take it; presenting without it");
                fg.active = false;
                fg.refused = true;
            }
        }
        self.wgpu_surface(d, window, size)
    }

    /// wgpu's own surface on [window].
    fn wgpu_surface(&self, d: &Descriptors, window: &ndk::native_window::NativeWindow, size: (u32, u32)) -> Result<(), String> {
        let surface = unsafe { d.wgpu_instance.create_surface_unsafe(crate::surface_target(window)) }.map_err(|e| e.to_string())?;
        let config = surface_config(&surface, &d.adapter, size);
        surface.configure(&d.device, &config);
        *self.surface.lock().unwrap() = Some(surface);
        *self.config.lock().unwrap() = config;
        *self.offscreen.lock().unwrap() = None;
        Ok(())
    }

    /// Hand Ruffle's Vulkan device to frame generation (nothing on OpenGL ES or in a build
    /// without it). [features] is what the device was made with (framegen::device_made).
    pub fn frame_gen_attach(&self, d: &Descriptors, features: u32) {
        if d.adapter.get_info().backend != wgpu::Backend::Vulkan {
            return;
        }
        if framegen::attach(&d.device, features) {
            self.fg.lock().unwrap().attached = true;
        }
    }

    /// New frame generation settings ([settings]), a new window ([window]), or neither (a frame
    /// found it broken): turn it on or off to match. Turning it on lets wgpu's surface go and
    /// gives the window to frame generation; turning it off gives it back to wgpu.
    pub fn update_frame_gen(&self, d: &Descriptors, window: Option<&ndk::native_window::NativeWindow>, settings: Option<framegen::Settings>) {
        let mut fg = self.fg.lock().unwrap();
        if let Some(s) = settings {
            if s != fg.settings {
                fg.refused = false;
            }
            framegen::configure(&s, framegen::display_rate());
            fg.settings = s;
        }
        if let Some(w) = window {
            if fg.window.as_ref().map(|x| x.ptr()) != Some(w.ptr()) {
                fg.refused = false;
                fg.broken = false;
            }
            fg.window = Some(w.clone());
        }
        let want = fg.attached && fg.settings.enabled && !fg.refused && !fg.broken && fg.window.is_some() && framegen::reason() == 0;
        if want && !fg.active {
            // wgpu's surface must be gone before the library can make one on the same window.
            *self.surface.lock().unwrap() = None;
            let window = fg.window.clone().unwrap();
            if framegen::set_window(&window) {
                fg.active = true;
                log::info!("frame generation: on");
            } else {
                log::warn!("frame generation: the window would not take it; presenting without it");
                fg.refused = true;
                drop(fg);
                let size = { let c = self.config.lock().unwrap(); (c.width, c.height) };
                if let Err(e) = self.wgpu_surface(d, &window, size) {
                    log::warn!("surface: {e}");
                }
            }
        } else if !want && fg.active {
            framegen::release_window();
            fg.active = false;
            fg.input = None;
            log::info!("frame generation: off");
            if let Some(window) = fg.window.clone() {
                drop(fg);
                let size = { let c = self.config.lock().unwrap(); (c.width, c.height) };
                if let Err(e) = self.wgpu_surface(d, &window, size) {
                    log::warn!("surface: {e}");
                }
            }
        }
    }

    /// A frame found frame generation's device or window gone: [update_frame_gen] with nothing
    /// new hands the window back to wgpu.
    pub fn frame_gen_broken(&self) -> bool {
        let fg = self.fg.lock().unwrap();
        fg.active && fg.broken
    }

    /// Frame generation presents right now.
    pub fn frame_gen_active(&self) -> bool {
        self.fg.lock().unwrap().active
    }

    /// The multiplier frame generation runs with (1 while it does not).
    pub fn frame_gen_multiplier(&self) -> u32 {
        let fg = self.fg.lock().unwrap();
        if fg.active { fg.settings.multiplier.clamp(2, 4) } else { 1 }
    }

    /// Load a .slangp preset, or turn shaders off with `None`.
    pub fn set_preset(&self, d: &Descriptors, path: Option<&str>) -> Result<(), String> {
        let chain = match path {
            None => None,
            Some(p) => Some(
                FilterChainWgpu::load_from_path(p, librashader_common::shader_features::ShaderFeatures::NONE, &d.device, &d.queue, None)
                    .map_err(|e| format!("shader preset {p}: {e}"))?,
            ),
        };
        *self.chain.lock().unwrap() = chain;
        *self.offscreen.lock().unwrap() = None;
        Ok(())
    }

    /// Set one of the preset's parameters (its `#pragma parameter` value).
    pub fn set_parameter(&self, name: &str, value: f32) {
        if let Some(chain) = self.chain.lock().unwrap().as_mut() {
            let _ = chain.parameters().set_parameter_value(name, value);
        }
    }

    fn offscreen_view(&self, device: &wgpu::Device) -> wgpu::TextureView {
        let config = self.config.lock().unwrap();
        let mut slot = self.offscreen.lock().unwrap();
        let fits = slot.as_ref().is_some_and(|(t, _)| t.width() == config.width && t.height() == config.height);
        if !fits {
            let texture = device.create_texture(&wgpu::TextureDescriptor {
                label: Some("ARMFlash shader input"),
                size: wgpu::Extent3d { width: config.width.max(1), height: config.height.max(1), depth_or_array_layers: 1 },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: remove_srgb(config.format),
                usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_SRC,
                view_formats: &[],
            });
            let view = texture.create_view(&Default::default());
            *slot = Some((texture, view));
        }
        slot.as_ref().unwrap().1.clone()
    }

    /// Frame generation's input: what Ruffle (or the chain) draws for it to present. Its last use
    /// in wgpu is always as a render target, so it is in COLOR_ATTACHMENT_OPTIMAL when frame
    /// generation reads it (and is put back so).
    fn frame_gen_input(&self, device: &wgpu::Device) -> wgpu::TextureView {
        let (width, height, format) = {
            let c = self.config.lock().unwrap();
            (c.width.max(1), c.height.max(1), remove_srgb(c.format))
        };
        let mut fg = self.fg.lock().unwrap();
        let fits = fg.input.as_ref().is_some_and(|(t, _)| t.width() == width && t.height() == height);
        if !fits {
            if fg.input.is_some() {
                // Frame generation's own submissions may still read the old one, and wgpu does
                // not know about them.
                framegen::idle();
            }
            let texture = device.create_texture(&wgpu::TextureDescriptor {
                label: Some("ARMFlash frame generation input"),
                size: wgpu::Extent3d { width, height, depth_or_array_layers: 1 },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format,
                usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_SRC,
                view_formats: &[],
            });
            let view = texture.create_view(&Default::default());
            fg.input = Some((texture, view));
        }
        fg.input.as_ref().unwrap().1.clone()
    }
}

impl RenderTarget for ShaderTarget {
    type Frame = ShaderFrame;

    fn resize(&mut self, device: &wgpu::Device, width: u32, height: u32) {
        let mut config = self.config.lock().unwrap();
        config.width = width;
        config.height = height;
        if let Some(surface) = self.surface.lock().unwrap().as_ref() {
            surface.configure(device, &config);
        }
        // Frame generation's swap chain follows the window by itself (it is rebuilt when the
        // window says it is out of date), and its input follows the config on the next frame.
    }

    fn format(&self) -> wgpu::TextureFormat {
        remove_srgb(self.config.lock().unwrap().format)
    }

    fn width(&self) -> u32 {
        self.config.lock().unwrap().width
    }

    fn height(&self) -> u32 {
        self.config.lock().unwrap().height
    }

    fn get_next_texture(&mut self) -> Option<Self::Frame> {
        let device = OFFSCREEN_DEVICE.lock().unwrap().clone();
        // Frame generation presents: Ruffle draws into its input (or the chain does).
        if self.frame_gen_active() {
            let device = device?;
            let input = self.frame_gen_input(&device);
            let chained = self.chain.lock().unwrap().is_some();
            let view = if chained { self.offscreen_view(&device) } else { input.clone() };
            return Some(ShaderFrame { surface: None, surface_view: input, view, chained });
        }
        // No window (in the background): nothing to draw on.
        let current = self.surface.lock().unwrap().as_ref().map(|s| s.get_current_texture())?;
        let texture = match current {
            wgpu::CurrentSurfaceTexture::Success(t) | wgpu::CurrentSurfaceTexture::Suboptimal(t) => t,
            state => {
                log::warn!("no surface texture: {state:?}");
                return None;
            }
        };
        let format = remove_srgb(self.config.lock().unwrap().format);
        let surface_view = texture.texture.create_view(&wgpu::TextureViewDescriptor { format: Some(format), ..Default::default() });
        // With a chain, Ruffle draws offscreen (made on the renderer's device) and the chain
        // draws that onto the window in submit().
        let chained = self.chain.lock().unwrap().is_some() && device.is_some();
        let view = match (&device, chained) {
            (Some(d), true) => self.offscreen_view(d),
            _ => surface_view.clone(),
        };
        Some(ShaderFrame { surface: Some(texture), surface_view, view, chained })
    }

    fn submit<I: IntoIterator<Item = wgpu::CommandBuffer>>(
        &self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        command_buffers: I,
        frame: Self::Frame,
    ) -> wgpu::SubmissionIndex {
        let mut index = queue.submit(command_buffers);
        if frame.chained {
            let input = self.offscreen.lock().unwrap().as_ref().map(|(t, _)| t.clone());
            if let (Some(input), Some(chain)) = (input, self.chain.lock().unwrap().as_mut()) {
                let config = self.config.lock().unwrap().clone();
                let size = Size::new(config.width, config.height);
                let output = WgpuOutputView::new_from_raw(&frame.surface_view, size, remove_srgb(config.format));
                let viewport = librashader_common::Viewport { x: 0.0, y: 0.0, mvp: None, output, size };
                let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("ARMFlash shader chain") });
                let n = self.frames.fetch_add(1, Ordering::Relaxed);
                if let Err(e) = chain.frame(&input, &viewport, &mut encoder, n, None) {
                    log::warn!("shader frame: {e}");
                }
                index = queue.submit([encoder.finish()]);
            }
        }
        match frame.surface {
            Some(surface) => queue.present(surface),
            None => {
                // The frame is drawn by the work just submitted on this queue; frame generation's
                // own submission comes after it on the same queue, on this thread.
                let input = self.fg.lock().unwrap().input.as_ref().map(|(t, _)| t.clone());
                if let Some(input) = input {
                    if framegen::present(&input, framegen::content_rate(), framegen::display_rate()) < 0 {
                        log::warn!("frame generation: its window or device is gone; presenting without it");
                        self.fg.lock().unwrap().broken = true;
                    }
                }
            }
        }
        index
    }
}

/// The device the offscreen texture is made on (set once the renderer exists).
pub static OFFSCREEN_DEVICE: Mutex<Option<wgpu::Device>> = Mutex::new(None);

/// A renderer for an Android window, as `WgpuRenderBackend::for_window_unsafe` builds one, but
/// on [`ShaderTarget`].
///
/// # Safety
/// `window` must stay valid as for `wgpu::SurfaceTargetUnsafe`.
pub unsafe fn renderer_for_window(
    window: wgpu::SurfaceTargetUnsafe,
    size: (u32, u32),
    backends: wgpu::Backends,
) -> Result<WgpuRenderBackend<ShaderTarget>, String> {
    let instance = create_wgpu_instance(backends, wgpu::BackendOptions::default(), None);
    let surface = unsafe { instance.create_surface_unsafe(window) }.map_err(|e| e.to_string())?;
    let (adapter, device, queue) = futures::executor::block_on(request_adapter_and_device(
        backends,
        &instance,
        Some(&surface),
        wgpu::PowerPreference::HighPerformance,
    ))
    .map_err(|e| e.to_string())?;
    let descriptors = Descriptors::new(instance, adapter, device, queue);
    let target = ShaderTarget::new(surface, &descriptors.adapter, size, &descriptors.device);
    *OFFSCREEN_DEVICE.lock().unwrap() = Some(descriptors.device.clone());
    WgpuRenderBackend::new(Arc::new(descriptors), target).map_err(|e| e.to_string())
}
