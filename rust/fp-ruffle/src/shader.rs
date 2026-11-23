//! RetroArch (.slangp) shaders on native Flash, through librashader's wgpu runtime.
//!
//! [`ShaderTarget`] is Ruffle's swap-chain target with one difference: when a shader preset is
//! loaded, Ruffle draws each frame into an offscreen texture and the preset's filter chain
//! draws that onto the window. Without a preset Ruffle draws straight into the window, exactly
//! as `SwapChainTarget` does. The surface sits behind a lock so a new window (after the app was
//! in the background) can be attached through the backend's shared `target()`.

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
    /// None while the app has no window (in the background): the surface is let go with its
    /// window, as Vulkan allows one surface per Android window.
    surface: Mutex<Option<wgpu::Surface<'static>>>,
    config: Mutex<wgpu::SurfaceConfiguration>,
    chain: Mutex<Option<FilterChainWgpu>>,
    /// Ruffle's frame when a chain runs: same size and format as the window.
    offscreen: Mutex<Option<(wgpu::Texture, wgpu::TextureView)>>,
    frames: AtomicUsize,
}

impl std::fmt::Debug for ShaderTarget {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ShaderTarget").finish_non_exhaustive()
    }
}

#[derive(Debug)]
pub struct ShaderFrame {
    surface: wgpu::SurfaceTexture,
    surface_view: wgpu::TextureView,
    /// Where Ruffle draws: the offscreen texture with a chain, else the window.
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
        }
    }

    /// Let the window's surface go: the window is going away (the app left the screen). A second
    /// surface on the same window fails (VK_ERROR_NATIVE_WINDOW_IN_USE_KHR) and wgpu panics on it.
    pub fn release(&self) {
        *self.surface.lock().unwrap() = None;
        *self.offscreen.lock().unwrap() = None;
    }

    /// A new window surface (the old one, if still here, is let go first).
    pub fn recreate(&self, d: &Descriptors, target: wgpu::SurfaceTargetUnsafe, size: (u32, u32)) -> Result<(), String> {
        self.release();
        let surface = unsafe { d.wgpu_instance.create_surface_unsafe(target) }.map_err(|e| e.to_string())?;
        let config = surface_config(&surface, &d.adapter, size);
        surface.configure(&d.device, &config);
        *self.surface.lock().unwrap() = Some(surface);
        *self.config.lock().unwrap() = config;
        *self.offscreen.lock().unwrap() = None;
        Ok(())
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
        let device = OFFSCREEN_DEVICE.lock().unwrap().clone();
        let chained = self.chain.lock().unwrap().is_some() && device.is_some();
        let view = match (&device, chained) {
            (Some(d), true) => self.offscreen_view(d),
            _ => surface_view.clone(),
        };
        Some(ShaderFrame { surface: texture, surface_view, view, chained })
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
        queue.present(frame.surface);
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
