// SPDX-License-Identifier: GPL-3.0-or-later
//
// C ABI for libarmflash_lsfg.so: LSFG (Lossless Scaling frame generation) for native Flash,
// reached by dlopen/dlsym from fp-ruffle (rust/fp-ruffle/src/framegen.rs), as ARMSX2 reached its
// libarmsx2_lsfg.so (3rdparty/lsfg/armsx2_lsfg_shim.h).
//
// WHY A SEPARATE .so, AND WHY C
//
// - The Play build must not carry frame generation at all, so the library is built for the
//   GitHub flavour only and the player finds out at run time whether it is there.
// - The frame-generation code (ARMSX2's FrameGen/, from Eden and lsfg-vk) calls Vulkan by its
//   plain names. Here those are this library's own hidden function pointers, loaded from the
//   player's vkGetInstanceProcAddr, so they cannot meet libvulkan's exported vk* functions
//   (ARMSX2's volk collision) and a custom driver is called directly.
// - Nothing richer than a pointer, an integer or a float crosses this boundary: the player is
//   Rust and this library carries its own static libc++. Nothing throws across it either.
//
// THE MODEL
//
// Frame generation runs on the player's own wgpu device and queue, and only on the player's
// render thread, the only thread that uses that queue. While it is on, this library owns the
// window's swap chain (the player lets go of wgpu's surface): each rendered frame is handed to
// armflash_lsfg_present(), which copies it, interpolates up to three frames between it and the
// previous one, and presents those, then the frame itself. Every call below except the status
// queries must be made on the render thread.
//
// NOTHING PROPRIETARY IS BUNDLED. The interpolation shaders are read at run time out of the
// user's own Lossless.dll, which they pick in ARMFlash and which is copied into the app's files.

#ifndef ARMFLASH_LSFG_H
#define ARMFLASH_LSFG_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ARMFLASH_LSFG_ABI_VERSION 1u

/// What the player's device was created with (wgpu-hal's ARMFLASH_FRAME_GENERATION_ENABLED).
#define ARMFLASH_LSFG_FEATURE_MEMORY_MODEL 1u
#define ARMFLASH_LSFG_FEATURE_NULL_DESCRIPTOR 2u
#define ARMFLASH_LSFG_FEATURE_FLOAT16 4u

typedef struct ArmflashLsfgDevice {
	uint32_t abi;                  ///< ARMFLASH_LSFG_ABI_VERSION
	uint32_t queue_family;         ///< the family of [queue]
	void* get_instance_proc_addr;  ///< PFN_vkGetInstanceProcAddr the instance was made with
	uint64_t instance;             ///< VkInstance
	uint64_t physical_device;      ///< VkPhysicalDevice
	uint64_t device;               ///< VkDevice
	uint64_t queue;                ///< VkQueue the player submits on
	uint32_t enabled_features;     ///< ARMFLASH_LSFG_FEATURE_* bits
} ArmflashLsfgDevice;

typedef struct ArmflashLsfgSettings {
	uint32_t abi;          ///< ARMFLASH_LSFG_ABI_VERSION
	uint32_t enabled;      ///< 0 off, 1 on
	uint32_t multiplier;   ///< frames shown per rendered frame: 2, 3 or 4
	uint32_t flow_scale;   ///< motion detail, 25..100; 100 = automatic
	uint32_t fp16;         ///< 1: the half-precision shaders (needs FEATURE_FLOAT16)
	uint32_t target_rate;  ///< adaptive pacing's target, Hz; 0 = a fixed multiplier
	const char* dll_path;  ///< the user's Lossless.dll, a plain file path ("" = none)
	const char* cache_dir; ///< where the translated shaders are cached
} ArmflashLsfgSettings;

/// Why frame generation cannot run, in the order it is decided (ARMSX2's GSLsfg::Unavailable;
/// the app's settings screens read these numbers, so they never change meaning).
enum {
	ARMFLASH_LSFG_AVAILABLE = 0,
	ARMFLASH_LSFG_NOT_COMPILED_IN = 1, ///< never returned here: the library is missing
	ARMFLASH_LSFG_NOT_VULKAN = 2,      ///< no device attached (OpenGL ES, or not started yet)
	ARMFLASH_LSFG_GPU_UNSUPPORTED = 3, ///< needs Adreno 7xx or newer, or a 6xx on Turnip
	ARMFLASH_LSFG_NO_DLL = 4,
	ARMFLASH_LSFG_DLL_UNREADABLE = 5,
	ARMFLASH_LSFG_INIT_FAILED = 6,     ///< device features, resources or shaders failed
};

uint32_t armflash_lsfg_abi_version(void);

/// The player's device. 0 on success. Once per device; armflash_lsfg_detach() before it is gone.
int32_t armflash_lsfg_attach(const ArmflashLsfgDevice* device);
void armflash_lsfg_detach(void);

/// The settings, at start and whenever they change. Strings are copied.
void armflash_lsfg_configure(const ArmflashLsfgSettings* settings);

/// One of ARMFLASH_LSFG_* above.
int32_t armflash_lsfg_unavailable_reason(void);

/// Present on [window] (an ANativeWindow*, acquired for as long as it is held). 0 on success;
/// on failure the caller keeps presenting through its own surface.
int32_t armflash_lsfg_set_window(void* window);
/// Let the window go (it is going away, or frame generation is being turned off). Waits for the
/// GPU, so nothing still in flight uses the swap chain.
void armflash_lsfg_release_window(void);

/// Present one rendered frame. [image] is a VkImage of [width]x[height] in [format] (a VkFormat),
/// in COLOR_ATTACHMENT_OPTIMAL, rendered by work already submitted on the queue; it is read and
/// left in that layout. [content_rate] is the game's frame rate (0 if unknown) and [display_rate]
/// the screen's: no more frames are generated than the screen can show between two of the
/// game's. Returns 1 when presented, 0 when this frame could not be (try the next one), and a
/// negative number when the device or the surface is gone.
int32_t armflash_lsfg_present(uint64_t image, uint32_t width, uint32_t height, uint32_t format,
	float content_rate, float display_rate);

/// Wait until no submitted work reads a frame image, before the caller destroys one.
void armflash_lsfg_idle(void);

/// The performance overlay's line, e.g. "LSFG: 60.00 fp16 · 1.23 ms"; "" while switched off.
/// Writes at most [capacity] bytes including the terminator; returns the length. Any thread.
uint32_t armflash_lsfg_status(char* out, uint32_t capacity);

/// Frames reaching the screen per second (rendered plus generated), 0 before the first second.
/// Any thread.
float armflash_lsfg_display_fps(void);

/// 1 when [path] is a readable PE image (structural only; any thread).
int32_t armflash_lsfg_check_dll(const char* path);

#ifdef __cplusplus
} // extern "C"
#endif

#endif // ARMFLASH_LSFG_H
