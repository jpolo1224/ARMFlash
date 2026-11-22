//! Ruffle on a custom Vulkan driver (Turnip and other adrenotools packs).
//!
//! The app opens the driver with libadrenotools (libfpdriver.so) and gives us its
//! vkGetInstanceProcAddr. wgpu normally loads the system libvulkan itself, so here the Vulkan
//! instance is created through the driver's entry point instead, with the extensions wgpu asks
//! for, handed to wgpu as a raw instance, and Ruffle's renderer is built on it exactly as
//! `WgpuRenderBackend::for_window_unsafe` would build it.

use std::sync::Arc;

use ash::vk;
use ruffle_render_wgpu::backend::{request_adapter_and_device, WgpuRenderBackend};
use ruffle_render_wgpu::descriptors::Descriptors;
use ruffle_render_wgpu::target::SwapChainTarget;

/// # Safety
/// `gipa` must be the address of a live vkGetInstanceProcAddr, and `target` must stay valid
/// as for `wgpu::SurfaceTargetUnsafe`.
pub unsafe fn renderer_with_driver(
    gipa: usize,
    target: wgpu::SurfaceTargetUnsafe,
    size: (u32, u32),
    android_sdk: u32,
) -> Result<WgpuRenderBackend<SwapChainTarget>, String> {
    let gipa: vk::PFN_vkGetInstanceProcAddr = unsafe { std::mem::transmute(gipa) };
    let entry = unsafe { ash::Entry::from_static_fn(ash::StaticFn { get_instance_proc_addr: gipa }) };

    let available = unsafe { entry.try_enumerate_instance_version() }.ok().flatten().unwrap_or(vk::API_VERSION_1_0);
    let api = available.min(vk::API_VERSION_1_3);
    let flags = wgpu::InstanceFlags::empty();
    let extensions = wgpu_hal::vulkan::Instance::desired_extensions(&entry, api, flags).map_err(|e| format!("extensions: {e}"))?;

    let app = vk::ApplicationInfo::default()
        .application_name(c"FlashARM")
        .engine_name(c"Ruffle")
        .api_version(api);
    let names: Vec<*const std::ffi::c_char> = extensions.iter().map(|e| e.as_ptr()).collect();
    let info = vk::InstanceCreateInfo::default().application_info(&app).enabled_extension_names(&names);
    let raw = unsafe { entry.create_instance(&info, None) }.map_err(|e| format!("vkCreateInstance: {e}"))?;

    let hal = unsafe {
        wgpu_hal::vulkan::Instance::from_raw(entry, raw, api, android_sdk, None, extensions, flags, Default::default(), false, None)
    }
    .map_err(|e| format!("wgpu-hal: {e}"))?;
    let instance = unsafe { wgpu::Instance::from_hal::<wgpu_hal::api::Vulkan>(hal) };

    let surface = unsafe { instance.create_surface_unsafe(target) }.map_err(|e| format!("surface: {e}"))?;
    let (adapter, device, queue) = futures::executor::block_on(request_adapter_and_device(
        wgpu::Backends::VULKAN,
        &instance,
        Some(&surface),
        wgpu::PowerPreference::HighPerformance,
    ))
    .map_err(|e| format!("device: {e}"))?;
    let descriptors = Descriptors::new(instance, adapter, device, queue);
    let target = SwapChainTarget::new(surface, &descriptors.adapter, size, &descriptors.device);
    WgpuRenderBackend::new(Arc::new(descriptors), target).map_err(|e| format!("renderer: {e}"))
}
