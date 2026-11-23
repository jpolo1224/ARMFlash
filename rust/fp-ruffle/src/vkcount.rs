//! Counts live Vulkan objects of the kinds wgpu's own counters leave out (descriptor pools,
//! framebuffers, memory allocations, command pools and more), by sitting between wgpu and the
//! custom driver's vkGetInstanceProcAddr: each create adds one, each destroy takes one away.
//! For the memory log, to find what grows when the process's mappings climb.

use ash::vk;
use std::ffi::{c_char, c_void, CStr};
use std::sync::atomic::{AtomicIsize, AtomicUsize, Ordering};

type CreateFn = unsafe extern "system" fn(vk::Device, *const c_void, *const c_void, *mut u64) -> vk::Result;
type DestroyFn = unsafe extern "system" fn(vk::Device, u64, *const c_void);

static REAL_GIPA: AtomicUsize = AtomicUsize::new(0);
static REAL_GDPA: AtomicUsize = AtomicUsize::new(0);

macro_rules! counted {
    ($( $i:literal $label:literal $create:literal $destroy:literal ),* $(,)?) => {
        const N: usize = [$($i),*].len();
        static LIVE: [AtomicIsize; N] = [const { AtomicIsize::new(0) }; N];
        static REAL_CREATE: [AtomicUsize; N] = [const { AtomicUsize::new(0) }; N];
        static REAL_DESTROY: [AtomicUsize; N] = [const { AtomicUsize::new(0) }; N];
        const LABELS: [&str; N] = [$($label),*];
        const CREATE_NAMES: [&str; N] = [$($create),*];
        const DESTROY_NAMES: [&str; N] = [$($destroy),*];

        paste_hooks!($($i)*);
    };
}

macro_rules! paste_hooks {
    ($($i:literal)*) => {
        fn create_hook(i: usize) -> vk::PFN_vkVoidFunction {
            $(
                if i == $i {
                    unsafe extern "system" fn f(d: vk::Device, info: *const c_void, alloc: *const c_void, out: *mut u64) -> vk::Result {
                        let real: CreateFn = unsafe { std::mem::transmute(REAL_CREATE[$i].load(Ordering::Relaxed)) };
                        let r = unsafe { real(d, info, alloc, out) };
                        if r == vk::Result::SUCCESS { LIVE[$i].fetch_add(1, Ordering::Relaxed); }
                        r
                    }
                    return Some(unsafe { std::mem::transmute::<CreateFn, unsafe extern "system" fn()>(f) });
                }
            )*
            None
        }
        fn destroy_hook(i: usize) -> vk::PFN_vkVoidFunction {
            $(
                if i == $i {
                    unsafe extern "system" fn f(d: vk::Device, h: u64, alloc: *const c_void) {
                        let real: DestroyFn = unsafe { std::mem::transmute(REAL_DESTROY[$i].load(Ordering::Relaxed)) };
                        if h != 0 { LIVE[$i].fetch_sub(1, Ordering::Relaxed); }
                        unsafe { real(d, h, alloc) }
                    }
                    return Some(unsafe { std::mem::transmute::<DestroyFn, unsafe extern "system" fn()>(f) });
                }
            )*
            None
        }
    };
}

counted! {
    0 "memory" "vkAllocateMemory" "vkFreeMemory",
    1 "desc_pools" "vkCreateDescriptorPool" "vkDestroyDescriptorPool",
    2 "framebuffers" "vkCreateFramebuffer" "vkDestroyFramebuffer",
    3 "image_views" "vkCreateImageView" "vkDestroyImageView",
    4 "images" "vkCreateImage" "vkDestroyImage",
    5 "buffers" "vkCreateBuffer" "vkDestroyBuffer",
    6 "cmd_pools" "vkCreateCommandPool" "vkDestroyCommandPool",
    7 "semaphores" "vkCreateSemaphore" "vkDestroySemaphore",
    8 "fences" "vkCreateFence" "vkDestroyFence",
    9 "render_passes" "vkCreateRenderPass" "vkDestroyRenderPass",
    10 "render_passes2" "vkCreateRenderPass2" "vkDestroyRenderPass",
    11 "query_pools" "vkCreateQueryPool" "vkDestroyQueryPool",
    12 "samplers" "vkCreateSampler" "vkDestroySampler",
    13 "swapchains" "vkCreateSwapchainKHR" "vkDestroySwapchainKHR",
    14 "pipeline_layouts" "vkCreatePipelineLayout" "vkDestroyPipelineLayout",
    15 "set_layouts" "vkCreateDescriptorSetLayout" "vkDestroyDescriptorSetLayout",
}

// Command buffers and pipelines come in arrays, so they get their own hooks.
static CMD_BUFFERS: AtomicIsize = AtomicIsize::new(0);
static PIPELINES: AtomicIsize = AtomicIsize::new(0);
static COMMAND_POOL_RESETS: AtomicIsize = AtomicIsize::new(0);
static REAL_ALLOC_CB: AtomicUsize = AtomicUsize::new(0);
static REAL_FREE_CB: AtomicUsize = AtomicUsize::new(0);
static REAL_GFX_PIPES: AtomicUsize = AtomicUsize::new(0);
static REAL_DESTROY_PIPE: AtomicUsize = AtomicUsize::new(0);
static REAL_RESET_POOL: AtomicUsize = AtomicUsize::new(0);

#[repr(C)]
struct CbAllocInfo { s_type: i32, p_next: *const c_void, pool: u64, level: i32, count: u32 }

unsafe extern "system" fn alloc_cb(d: vk::Device, info: *const CbAllocInfo, out: *mut *mut c_void) -> vk::Result {
    let real: unsafe extern "system" fn(vk::Device, *const CbAllocInfo, *mut *mut c_void) -> vk::Result = unsafe { std::mem::transmute(REAL_ALLOC_CB.load(Ordering::Relaxed)) };
    let r = unsafe { real(d, info, out) };
    if r == vk::Result::SUCCESS {
        let n = unsafe { (*info).count } as isize;
        CMD_BUFFERS.fetch_add(n, Ordering::Relaxed);
        *PER_POOL.lock().unwrap().entry(unsafe { (*info).pool }).or_default() += n;
    }
    r
}
/// Live command buffers per pool: destroying a pool frees the ones it still holds.
static PER_POOL: std::sync::Mutex<std::collections::BTreeMap<u64, isize>> = std::sync::Mutex::new(std::collections::BTreeMap::new());
static REAL_DESTROY_POOL: AtomicUsize = AtomicUsize::new(0);
unsafe extern "system" fn destroy_pool(d: vk::Device, pool: u64, alloc: *const c_void) {
    let real: DestroyFn = unsafe { std::mem::transmute(REAL_DESTROY_POOL.load(Ordering::Relaxed)) };
    if pool != 0 {
        LIVE[6].fetch_sub(1, Ordering::Relaxed);
        if let Some(n) = PER_POOL.lock().unwrap().remove(&pool) { CMD_BUFFERS.fetch_sub(n, Ordering::Relaxed); }
    }
    unsafe { real(d, pool, alloc) }
}
unsafe extern "system" fn free_cb(d: vk::Device, pool: u64, n: u32, bufs: *const *mut c_void) {
    let real: unsafe extern "system" fn(vk::Device, u64, u32, *const *mut c_void) = unsafe { std::mem::transmute(REAL_FREE_CB.load(Ordering::Relaxed)) };
    CMD_BUFFERS.fetch_sub(n as isize, Ordering::Relaxed);
    if let Some(c) = PER_POOL.lock().unwrap().get_mut(&pool) { *c -= n as isize; }
    unsafe { real(d, pool, n, bufs) }
}
unsafe extern "system" fn gfx_pipes(d: vk::Device, cache: u64, n: u32, infos: *const c_void, alloc: *const c_void, out: *mut u64) -> vk::Result {
    let real: unsafe extern "system" fn(vk::Device, u64, u32, *const c_void, *const c_void, *mut u64) -> vk::Result = unsafe { std::mem::transmute(REAL_GFX_PIPES.load(Ordering::Relaxed)) };
    let r = unsafe { real(d, cache, n, infos, alloc, out) };
    if r == vk::Result::SUCCESS { PIPELINES.fetch_add(n as isize, Ordering::Relaxed); }
    r
}
unsafe extern "system" fn destroy_pipe(d: vk::Device, p: u64, alloc: *const c_void) {
    let real: DestroyFn = unsafe { std::mem::transmute(REAL_DESTROY_PIPE.load(Ordering::Relaxed)) };
    if p != 0 { PIPELINES.fetch_sub(1, Ordering::Relaxed); }
    unsafe { real(d, p, alloc) }
}
unsafe extern "system" fn reset_pool(d: vk::Device, pool: u64, flags: u32) -> vk::Result {
    let real: unsafe extern "system" fn(vk::Device, u64, u32) -> vk::Result = unsafe { std::mem::transmute(REAL_RESET_POOL.load(Ordering::Relaxed)) };
    COMMAND_POOL_RESETS.fetch_add(1, Ordering::Relaxed);
    unsafe { real(d, pool, flags) }
}

fn special(name: &str, real: unsafe extern "system" fn()) -> Option<vk::PFN_vkVoidFunction> {
    let (slot, hook): (&AtomicUsize, usize) = match name {
        "vkAllocateCommandBuffers" => (&REAL_ALLOC_CB, alloc_cb as usize),
        "vkFreeCommandBuffers" => (&REAL_FREE_CB, free_cb as usize),
        "vkCreateGraphicsPipelines" => (&REAL_GFX_PIPES, gfx_pipes as usize),
        "vkDestroyPipeline" => (&REAL_DESTROY_PIPE, destroy_pipe as usize),
        "vkResetCommandPool" => (&REAL_RESET_POOL, reset_pool as usize),
        "vkDestroyCommandPool" => (&REAL_DESTROY_POOL, destroy_pool as usize),
        _ => return None,
    };
    slot.store(real as usize, Ordering::Relaxed);
    Some(Some(unsafe { std::mem::transmute::<usize, unsafe extern "system" fn()>(hook) }))
}

/// Hook a looked-up function, remembering the driver's own; None when it's not one we count.
fn hook(name: &str, real: vk::PFN_vkVoidFunction) -> Option<vk::PFN_vkVoidFunction> {
    let real = real?;
    if let Some(h) = special(name, real) {
        return Some(h);
    }
    for i in 0..N {
        if name == CREATE_NAMES[i] {
            REAL_CREATE[i].store(real as usize, Ordering::Relaxed);
            return Some(create_hook(i));
        }
        if name == DESTROY_NAMES[i] {
            // Render passes share one destroy; keep the first index that asked for it.
            REAL_DESTROY[i].store(real as usize, Ordering::Relaxed);
            if i == 10 { return None; }
            return Some(destroy_hook(i));
        }
    }
    None
}

unsafe extern "system" fn gdpa(device: vk::Device, name: *const c_char) -> vk::PFN_vkVoidFunction {
    let real: vk::PFN_vkGetDeviceProcAddr = unsafe { std::mem::transmute(REAL_GDPA.load(Ordering::Relaxed)) };
    let f = unsafe { real(device, name) };
    let n = unsafe { CStr::from_ptr(name) }.to_str().unwrap_or("");
    hook(n, f).unwrap_or(f)
}

/// The vkGetInstanceProcAddr wgpu should use in place of [real].
pub unsafe extern "system" fn gipa(instance: vk::Instance, name: *const c_char) -> vk::PFN_vkVoidFunction {
    let real: vk::PFN_vkGetInstanceProcAddr = unsafe { std::mem::transmute(REAL_GIPA.load(Ordering::Relaxed)) };
    let f = unsafe { real(instance, name) };
    let n = unsafe { CStr::from_ptr(name) }.to_str().unwrap_or("");
    if n == "vkGetDeviceProcAddr" {
        if let Some(f) = f {
            REAL_GDPA.store(f as usize, Ordering::Relaxed);
            return Some(unsafe { std::mem::transmute::<unsafe extern "system" fn(vk::Device, *const c_char) -> vk::PFN_vkVoidFunction, unsafe extern "system" fn()>(gdpa) });
        }
    }
    hook(n, f).unwrap_or(f)
}

pub fn install(real: vk::PFN_vkGetInstanceProcAddr) -> vk::PFN_vkGetInstanceProcAddr {
    REAL_GIPA.store(real as usize, Ordering::Relaxed);
    gipa
}

pub fn active() -> bool {
    REAL_GIPA.load(Ordering::Relaxed) != 0
}

/// "memory 812 desc_pools 40 ..." for the memory log.
pub fn summary() -> String {
    let mut s = (0..N).filter(|&i| i != 10).map(|i| format!("{} {}", LABELS[i], LIVE[i].load(Ordering::Relaxed) + if i == 9 { LIVE[10].load(Ordering::Relaxed) } else { 0 })).collect::<Vec<_>>().join(" ");
    s.push_str(&format!(
        " command_buffers {} pipelines {} pool_resets {}",
        CMD_BUFFERS.load(Ordering::Relaxed), PIPELINES.load(Ordering::Relaxed), COMMAND_POOL_RESETS.load(Ordering::Relaxed),
    ));
    s
}
