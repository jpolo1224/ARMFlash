// SPDX-License-Identifier: GPL-3.0-or-later
//
// libarmflash_lsfg.so: LSFG frame generation for native Flash. See armflash_lsfg.h for the model.
//
// This is ARMSX2's GSLsfg (pcsx2/GS/Renderers/Vulkan/GSLsfg.cpp, the "own device" path that runs
// the ported Eden/lsfg-vk passes in FrameGen/) moved onto ARMFlash's player. What changed, and why:
//
//  * The GS drew the real frame into a swap chain image it had acquired, and GSLsfg presented
//    generated frames around it. Ruffle draws into a texture of its own (fp-ruffle's frame
//    generation input), so here this library owns the window's swap chain outright: it acquires
//    an image for the real frame too and copies the frame in, the way GSLsfg copies generated
//    frames in.
//  * Every frame is copied into an image of ours first (ARMSX2's CaptureGameFrame), in
//    R8G8B8A8_UNORM, so the passes always see a format they can store to, and so Ruffle's texture
//    is free again at once. The copy measured as free in ARMSX2 and ARMSX3.
//  * No overlay redraw: ARMFlash's overlay is Android views over the game, not part of its frames.
//  * The present semaphores belong to swap chain images, not to frame slots: an image coming back
//    from an acquire proves the present that last waited on its semaphore is done with it. There
//    are fewer slots than images here, and two or more presents per slot.
//
// The ★ notes are ARMSX2's, kept where they still apply; read them before changing this.

#include "armflash_lsfg.h"

#include "FrameGen/FrameGen.h"
#include "FrameGen/LosslessDll.h"
#include "FrameGen/LsfgVkCompat.h"

#include "Config.h"
#include "common/Console.h"
#include "GS/Renderers/Vulkan/GSDeviceVK.h"
#include "GS/Renderers/Vulkan/VKLoader.h"

#include <android/native_window.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace
{
	constexpr size_t kMaxGenerations = VideoCore::FrameGen::MAX_GENERATIONS;
	/// The format frame generation works in (capture and generated images). Every Vulkan device
	/// can store to it, unlike B8G8R8A8.
	constexpr VkFormat kWorkFormat = VK_FORMAT_R8G8B8A8_UNORM;
	/// Frames whose work may be on the GPU at once. Two keeps the CPU a frame ahead without
	/// queueing more latency behind the swap chain's own.
	constexpr u32 kSlots = 2;

	enum class Unavailable : s32
	{
		Available = ARMFLASH_LSFG_AVAILABLE,
		NotVulkan = ARMFLASH_LSFG_NOT_VULKAN,
		GpuUnsupported = ARMFLASH_LSFG_GPU_UNSUPPORTED,
		NoDll = ARMFLASH_LSFG_NO_DLL,
		DllUnreadable = ARMFLASH_LSFG_DLL_UNREADABLE,
		InitFailed = ARMFLASH_LSFG_INIT_FAILED,
	};

	// --- device ------------------------------------------------------------------------------
	PFN_vkGetInstanceProcAddr s_gipa = nullptr;
	std::optional<GSDeviceVK> s_gs_device;
	std::optional<Vulkan::Device> s_device;
	std::optional<Vulkan::MemoryAllocator> s_allocator;
	std::optional<Vulkan::FrameGen> s_frame_gen;
	VkDevice s_vk_device = VK_NULL_HANDLE;
	/// ARMSX2's NoteRendererCapability: 6 for an Adreno 6xx on Turnip, 7/8 for 7xx/8xx, 9 for
	/// Adreno X, 0 for anything else.
	u32 s_adreno_generation = 0;

	// --- what the settings screens and the overlay read (any thread) --------------------------
	std::atomic<bool> s_attached{false};
	std::atomic<bool> s_enabled{false};
	std::atomic<bool> s_init_failed{false};
	std::atomic<bool> s_no_shaders{false};
	std::atomic<bool> s_dll_ok{false};
	std::atomic<bool> s_dll_set{false};
	std::atomic<bool> s_fp16_active{false};
	std::atomic<float> s_display_fps{0.0f};
	std::atomic<float> s_gpu_ms{0.0f};
	std::mutex s_failure_mutex;
	std::string s_failure_reason;

	// --- the window and its swap chain --------------------------------------------------------
	ANativeWindow* s_window = nullptr;
	VkSurfaceKHR s_surface = VK_NULL_HANDLE;
	VkSwapchainKHR s_swapchain = VK_NULL_HANDLE;
	std::vector<VkImage> s_swap_images;
	/// One per swap chain image: signalled by the submit that fills the image, waited on by its
	/// present. See the note at the top of the file.
	std::vector<VkSemaphore> s_present_sems;
	VkExtent2D s_swap_extent = {};
	VkFormat s_swap_format = VK_FORMAT_UNDEFINED;
	/// Images we may hold at once: the image count less the presentation engine's minimum, plus
	/// one. The real frame takes one; each generated frame one more.
	u32 s_acquirable = 0;
	u32 s_swap_built_for = 0; ///< the multiplier the image count was chosen for
	bool s_swap_stale = false; ///< out of date or suboptimal: rebuilt before the next frame
	VkFence s_acquire_fence = VK_NULL_HANDLE;

	// --- frame resources ------------------------------------------------------------------------
	struct WorkImage
	{
		Vulkan::vk::Image image;
		VkImageView view = VK_NULL_HANDLE;
	};
	/// The rendered frame, copied (CaptureGameFrame). Left in GENERAL.
	WorkImage s_capture;
	VkExtent2D s_capture_extent = {};

	struct FrameSlot
	{
		VkCommandBuffer cmd = VK_NULL_HANDLE;
		VkFence fence = VK_NULL_HANDLE;
		bool submitted = false;
		bool timed = false;
		/// ★ Per-SLOT generation images: the slot's fence proves its last command buffer is done
		/// before the slot is touched again, and only that buffer references these.
		std::array<WorkImage, kMaxGenerations> gen;
	};
	std::array<FrameSlot, kSlots> s_slots;
	VkCommandPool s_cmd_pool = VK_NULL_HANDLE;
	bool s_resources = false;
	u32 s_resources_mult = 0;

	VkQueryPool s_query_pool = VK_NULL_HANDLE;
	double s_ns_per_tick = 0.0;
	u64 s_tick_mask = 0;
	double s_gpu_ns_sum = 0.0;
	u32 s_gpu_samples = 0;
	u32 s_gpu_log_windows = 0;

	u64 s_frame_index = 0;

	// The one-second display-rate window.
	std::chrono::steady_clock::time_point s_fps_window_start;
	bool s_fps_window_open = false;
	u32 s_fps_real = 0;
	u32 s_fps_generated = 0;

	void NoteFramesDisplayed(u32 real, u32 generated)
	{
		s_fps_real += real;
		s_fps_generated += generated;
		const auto now = std::chrono::steady_clock::now();
		if (!s_fps_window_open)
		{
			s_fps_window_start = now;
			s_fps_window_open = true;
			return;
		}
		const double secs = std::chrono::duration<double>(now - s_fps_window_start).count();
		if (secs < 1.0)
			return;
		s_display_fps.store(static_cast<float>((s_fps_real + s_fps_generated) / secs), std::memory_order_relaxed);
		const float gpu_ms = s_gpu_samples ? static_cast<float>(s_gpu_ns_sum / s_gpu_samples / 1e6) : 0.0f;
		s_gpu_ms.store(gpu_ms, std::memory_order_relaxed);
		s_gpu_ns_sum = 0.0;
		s_gpu_samples = 0;
		// In the log every ten seconds, so a session can be measured without the overlay.
		if (++s_gpu_log_windows >= 10)
		{
			s_gpu_log_windows = 0;
			Console.WriteLn("LSFG: %.2f frames/s shown (%.2f rendered), GPU %.2f ms per frame at %ux%u%s.",
				(s_fps_real + s_fps_generated) / secs, s_fps_real / secs, gpu_ms, s_capture_extent.width,
				s_capture_extent.height, s_fp16_active.load(std::memory_order_relaxed) ? ", fp16" : "");
		}
		s_fps_window_start = now;
		s_fps_real = 0;
		s_fps_generated = 0;
	}

	/// A plain layout transition on a whole colour image (ARMSX2's TransitionImage).
	void TransitionImage(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to,
		VkAccessFlags src_access, VkAccessFlags dst_access)
	{
		VkImageMemoryBarrier barrier = {};
		barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		barrier.srcAccessMask = src_access;
		barrier.dstAccessMask = dst_access;
		barrier.oldLayout = from;
		barrier.newLayout = to;
		barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.image = image;
		barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
			0, 0, nullptr, 0, nullptr, 1, &barrier);
	}

	void SetFailure(const char* why)
	{
		std::unique_lock lock(s_failure_mutex);
		s_failure_reason = why ? why : "";
	}

	// --- resources -----------------------------------------------------------------------------

	bool CreateWorkImage(WorkImage& out, VkExtent2D extent, VkImageUsageFlags usage)
	{
		VkImageCreateInfo ici = {};
		ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
		ici.imageType = VK_IMAGE_TYPE_2D;
		ici.format = kWorkFormat;
		ici.extent = {extent.width, extent.height, 1};
		ici.mipLevels = 1;
		ici.arrayLayers = 1;
		ici.samples = VK_SAMPLE_COUNT_1_BIT;
		ici.tiling = VK_IMAGE_TILING_OPTIMAL;
		ici.usage = usage;
		ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
		ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		out.image = s_allocator->CreateImage(ici);
		if (!out.image)
			return false;
		VkImageViewCreateInfo vci = {};
		vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
		vci.image = *out.image;
		vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
		vci.format = kWorkFormat;
		vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
		return vkCreateImageView(s_vk_device, &vci, nullptr, &out.view) == VK_SUCCESS;
	}

	void DestroyWorkImage(WorkImage& w)
	{
		if (w.view != VK_NULL_HANDLE)
			vkDestroyImageView(s_vk_device, w.view, nullptr);
		w.view = VK_NULL_HANDLE;
		w.image = Vulkan::vk::Image();
	}

	/// Everything per frame. The caller has idled the device.
	void DestroyResources()
	{
		if (s_vk_device == VK_NULL_HANDLE)
			return;
		DestroyWorkImage(s_capture);
		s_capture_extent = {};
		for (FrameSlot& slot : s_slots)
		{
			for (WorkImage& g : slot.gen)
				DestroyWorkImage(g);
			if (slot.fence != VK_NULL_HANDLE)
				vkDestroyFence(s_vk_device, slot.fence, nullptr);
			slot = FrameSlot();
		}
		if (s_query_pool != VK_NULL_HANDLE)
			vkDestroyQueryPool(s_vk_device, s_query_pool, nullptr);
		s_query_pool = VK_NULL_HANDLE;
		s_gpu_ns_sum = 0.0;
		s_gpu_samples = 0;
		s_gpu_ms.store(0.0f, std::memory_order_relaxed);
		if (s_cmd_pool != VK_NULL_HANDLE)
			vkDestroyCommandPool(s_vk_device, s_cmd_pool, nullptr); // frees its command buffers
		s_cmd_pool = VK_NULL_HANDLE;
		s_resources = false;
		s_resources_mult = 0;
		s_frame_index = 0;
	}

	bool CreateResources(VkExtent2D extent, u32 multiplier)
	{
		const VkCommandPoolCreateInfo pool_ci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr,
			VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, s_gs_device->GetGraphicsQueueFamilyIndex()};
		if (vkCreateCommandPool(s_vk_device, &pool_ci, nullptr, &s_cmd_pool) != VK_SUCCESS)
			return false;

		const VkFenceCreateInfo fence_ci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0};
		// Generation images only for what the multiplier asks for; a change rebuilds these.
		const u32 per_frame = std::min<u32>(std::max<u32>(multiplier, 2u) - 1u, kMaxGenerations);
		for (FrameSlot& slot : s_slots)
		{
			const VkCommandBufferAllocateInfo cmd_ai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr,
				s_cmd_pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
			if (vkAllocateCommandBuffers(s_vk_device, &cmd_ai, &slot.cmd) != VK_SUCCESS)
				return false;
			// Created UNSIGNALLED, with `submitted` false: the first use must not wait on a fence
			// that was never submitted, which would block forever.
			if (vkCreateFence(s_vk_device, &fence_ci, nullptr, &slot.fence) != VK_SUCCESS)
				return false;
			for (u32 i = 0; i < per_frame; i++)
			{
				// STORAGE to be dispatched into, TRANSFER_SRC to be copied out of. ★ Never the swap
				// chain image itself: storage writes into a WSI image crashed Turnip at submit in
				// ARMSX2, while the stock driver tolerated them.
				if (!CreateWorkImage(slot.gen[i], extent, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT))
					return false;
			}
		}
		if (!CreateWorkImage(s_capture, extent,
				VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT))
			return false;
		s_capture_extent = extent;

		// Two timestamps per slot for frame generation's GPU time, where the queue has them.
		const VkPhysicalDeviceLimits& limits = s_gs_device->GetDeviceProperties().limits;
		u32 family_count = 0;
		vkGetPhysicalDeviceQueueFamilyProperties(s_gs_device->GetPhysicalDevice(), &family_count, nullptr);
		std::vector<VkQueueFamilyProperties> families(family_count);
		vkGetPhysicalDeviceQueueFamilyProperties(s_gs_device->GetPhysicalDevice(), &family_count, families.data());
		const u32 family = s_gs_device->GetGraphicsQueueFamilyIndex();
		const u32 valid_bits = family < family_count ? families[family].timestampValidBits : 0;
		if (limits.timestampComputeAndGraphics && limits.timestampPeriod > 0.0f && valid_bits > 0)
		{
			const VkQueryPoolCreateInfo query_ci = {VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO, nullptr, 0,
				VK_QUERY_TYPE_TIMESTAMP, 2 * kSlots, 0};
			if (vkCreateQueryPool(s_vk_device, &query_ci, nullptr, &s_query_pool) == VK_SUCCESS)
			{
				s_ns_per_tick = limits.timestampPeriod;
				s_tick_mask = valid_bits >= 64 ? ~0ull : ((1ull << valid_bits) - 1);
			}
		}
		s_resources = true;
		s_resources_mult = multiplier;
		s_frame_index = 0;
		return true;
	}

	// --- the swap chain -------------------------------------------------------------------------

	void DestroySwapchain()
	{
		for (VkSemaphore sem : s_present_sems)
			vkDestroySemaphore(s_vk_device, sem, nullptr);
		s_present_sems.clear();
		s_swap_images.clear();
		if (s_swapchain != VK_NULL_HANDLE)
			vkDestroySwapchainKHR(s_vk_device, s_swapchain, nullptr);
		s_swapchain = VK_NULL_HANDLE;
		s_swap_extent = {};
		s_acquirable = 0;
		s_swap_built_for = 0;
	}

	/// (Re)builds the swap chain for the window's current size. FIFO, because MAILBOX and
	/// IMMEDIATE replace each generated frame with the real one presented after it; and as many
	/// images as the presentation engine needs plus one per generated frame, so the real frame and
	/// its generated frames can all be held at once.
	bool BuildSwapchain(VkExtent2D wanted, u32 multiplier)
	{
		const VkPhysicalDevice phys = s_gs_device->GetPhysicalDevice();
		VkBool32 supported = VK_FALSE;
		if (vkGetPhysicalDeviceSurfaceSupportKHR(phys, s_gs_device->GetGraphicsQueueFamilyIndex(), s_surface, &supported) != VK_SUCCESS || !supported)
		{
			Console.Error("LSFG: the player's queue cannot present to this window.");
			return false;
		}
		VkSurfaceCapabilitiesKHR caps = {};
		if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(phys, s_surface, &caps) != VK_SUCCESS)
			return false;

		u32 format_count = 0;
		vkGetPhysicalDeviceSurfaceFormatsKHR(phys, s_surface, &format_count, nullptr);
		std::vector<VkSurfaceFormatKHR> formats(format_count);
		vkGetPhysicalDeviceSurfaceFormatsKHR(phys, s_surface, &format_count, formats.data());
		// A UNORM format, as Ruffle's own swap chain: its colours are written as they are, so an
		// _SRGB one would change them on the copy.
		VkSurfaceFormatKHR format = {VK_FORMAT_UNDEFINED, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
		for (VkFormat want : {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM})
		{
			for (const VkSurfaceFormatKHR& f : formats)
			{
				if (f.format == want && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
				{
					format = f;
					break;
				}
			}
			if (format.format != VK_FORMAT_UNDEFINED)
				break;
		}
		if (format.format == VK_FORMAT_UNDEFINED)
		{
			Console.Error("LSFG: the window offers no RGBA8 or BGRA8 format.");
			return false;
		}
		if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT))
		{
			Console.Error("LSFG: the window's images cannot be copied into.");
			return false;
		}

		VkExtent2D extent = caps.currentExtent;
		if (extent.width == 0xFFFFFFFFu)
			extent = wanted;
		extent.width = std::clamp(extent.width, caps.minImageExtent.width, std::max(caps.minImageExtent.width, caps.maxImageExtent.width));
		extent.height = std::clamp(extent.height, caps.minImageExtent.height, std::max(caps.minImageExtent.height, caps.maxImageExtent.height));
		if (extent.width == 0 || extent.height == 0)
			return false;

		const u32 extra = std::min<u32>(std::max<u32>(multiplier, 2u) - 1u, kMaxGenerations);
		u32 count = std::max(caps.minImageCount, 2u) + extra;
		if (caps.maxImageCount != 0)
			count = std::min(count, caps.maxImageCount);

		// The compositor turns the picture, as with wgpu's own swap chain (pre-rotation and frame
		// generation broke each other in ARMSX3 1.6.4).
		const VkSurfaceTransformFlagBitsKHR transform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ?
			VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
		VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
		for (VkCompositeAlphaFlagBitsKHR a : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
				 VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR})
		{
			if (caps.supportedCompositeAlpha & a)
			{
				alpha = a;
				break;
			}
		}

		VkSwapchainCreateInfoKHR ci = {};
		ci.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
		ci.surface = s_surface;
		ci.minImageCount = count;
		ci.imageFormat = format.format;
		ci.imageColorSpace = format.colorSpace;
		ci.imageExtent = extent;
		ci.imageArrayLayers = 1;
		ci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
		ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
		ci.preTransform = transform;
		ci.compositeAlpha = alpha;
		ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
		ci.clipped = VK_TRUE;
		ci.oldSwapchain = s_swapchain;

		VkSwapchainKHR swapchain = VK_NULL_HANDLE;
		const VkResult res = vkCreateSwapchainKHR(s_vk_device, &ci, nullptr, &swapchain);
		// The old one is retired either way; nothing of ours is still using it (the caller idled).
		for (VkSemaphore sem : s_present_sems)
			vkDestroySemaphore(s_vk_device, sem, nullptr);
		s_present_sems.clear();
		if (s_swapchain != VK_NULL_HANDLE)
			vkDestroySwapchainKHR(s_vk_device, s_swapchain, nullptr);
		s_swapchain = VK_NULL_HANDLE;
		if (res != VK_SUCCESS)
		{
			Console.Error("LSFG: vkCreateSwapchainKHR() failed: %d", static_cast<int>(res));
			return false;
		}
		s_swapchain = swapchain;

		u32 image_count = 0;
		vkGetSwapchainImagesKHR(s_vk_device, s_swapchain, &image_count, nullptr);
		s_swap_images.resize(image_count);
		vkGetSwapchainImagesKHR(s_vk_device, s_swapchain, &image_count, s_swap_images.data());
		const VkSemaphoreCreateInfo sem_ci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, nullptr, 0};
		s_present_sems.resize(image_count, VK_NULL_HANDLE);
		for (VkSemaphore& sem : s_present_sems)
		{
			if (vkCreateSemaphore(s_vk_device, &sem_ci, nullptr, &sem) != VK_SUCCESS)
			{
				DestroySwapchain();
				return false;
			}
		}
		s_swap_extent = extent;
		s_swap_format = format.format;
		// ★ The budget is what Vulkan allows us to HOLD, not the image count: with min 3 and 3
		// images it is one, and acquiring past it is undefined behaviour, not a failed call.
		s_acquirable = image_count >= caps.minImageCount ? image_count - caps.minImageCount + 1 : 1;
		s_swap_built_for = multiplier;
		s_swap_stale = false;
		Console.WriteLn("LSFG: swap chain %ux%u, %u images (%u can be held), format %d.", extent.width, extent.height,
			image_count, s_acquirable, static_cast<int>(format.format));
		return true;
	}

	/// Acquires a swap chain image with a fence and waits for it on the CPU.
	///
	/// ★ A FENCE, not a semaphore: waiting on an acquire semaphore inside a submit is where Turnip
	/// segfaults (ARMSX2's GSLsfg, GSFG-Zero). ★ Bounded, but NOT zero: under FIFO nothing is free
	/// the instant it is asked for at steady state, and waiting for a display slot is the very
	/// mechanism that paces two presents per rendered frame.
	VkResult AcquireImage(u64 timeout_ns, u32* index)
	{
		const VkResult res = vkAcquireNextImageKHR(s_vk_device, s_swapchain, timeout_ns, VK_NULL_HANDLE, s_acquire_fence, index);
		if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR)
			return res;
		if (res == VK_SUBOPTIMAL_KHR)
			s_swap_stale = true;
		if (*index >= s_swap_images.size())
			return VK_ERROR_UNKNOWN;
		// An acquire that returned success always signals its fence, so this is bounded by the
		// presentation engine giving the image back, not by the timeout we passed.
		const VkResult wait = vkWaitForFences(s_vk_device, 1, &s_acquire_fence, VK_TRUE, 1000ull * 1000 * 1000);
		vkResetFences(s_vk_device, 1, &s_acquire_fence);
		return wait == VK_SUCCESS ? VK_SUCCESS : wait;
	}

	/// Copies [src] (in [src_layout]) into acquired swap chain image [index] and leaves it ready
	/// to present.
	void CopyToSwapImage(VkCommandBuffer cmd, VkImage src, VkImageLayout src_layout, VkExtent2D src_extent, u32 index)
	{
		const VkImage dst = s_swap_images[index];
		TransitionImage(cmd, dst, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
		VkImageBlit blit = {};
		blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
		blit.srcOffsets[1] = {static_cast<s32>(src_extent.width), static_cast<s32>(src_extent.height), 1};
		blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
		blit.dstOffsets[1] = {static_cast<s32>(s_swap_extent.width), static_cast<s32>(s_swap_extent.height), 1};
		const bool same = src_extent.width == s_swap_extent.width && src_extent.height == s_swap_extent.height;
		vkCmdBlitImage(cmd, src, src_layout, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
			same ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
		TransitionImage(cmd, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
			VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT);
	}

	/// ARMSX2's Adreno generation from the GPU's name: "Adreno (TM) 830", "Turnip Adreno (TM) 740".
	u32 AdrenoGeneration(const VkPhysicalDeviceProperties& props)
	{
		const char* name = props.deviceName;
		const char* adreno = std::strstr(name, "Adreno");
		if (!adreno)
			return 0;
		const char* p = adreno + 6;
		while (*p && !(*p >= '0' && *p <= '9') && *p != 'X')
			p++;
		if (*p == 'X')
			return 9;
		if (!(*p >= '0' && *p <= '9'))
			return 0;
		const u32 gen = static_cast<u32>(*p - '0');
		// A 6xx only on Turnip, where frame generation already runs on these GPUs elsewhere.
		if (gen == 6)
			return (std::strstr(name, "Turnip") || std::strstr(name, "turnip")) ? 6 : 0;
		return gen >= 7 ? gen : 0;
	}

	bool LooksLikeLosslessDll(const char* path)
	{
		// Structural only (ARMSX2's): "MZ" at 0 and a PE signature where the DOS header points. It
		// rejects an obviously wrong pick at import time; a PE that is not Lossless Scaling's fails
		// later with "no usable shaders", the message that case needs anyway.
		FILE* fp = path ? std::fopen(path, "rb") : nullptr;
		if (!fp)
			return false;
		u8 dos[0x40] = {};
		bool ok = std::fread(dos, sizeof(dos), 1, fp) == 1 && dos[0] == 'M' && dos[1] == 'Z';
		if (ok)
		{
			const u32 pe_off = static_cast<u32>(dos[0x3C]) | (static_cast<u32>(dos[0x3D]) << 8) |
							   (static_cast<u32>(dos[0x3E]) << 16) | (static_cast<u32>(dos[0x3F]) << 24);
			u8 sig[4] = {};
			ok = pe_off >= sizeof(dos) && pe_off <= 64u * 1024u * 1024u && std::fseek(fp, static_cast<long>(pe_off), SEEK_SET) == 0 &&
				 std::fread(sig, sizeof(sig), 1, fp) == 1 && sig[0] == 'P' && sig[1] == 'E' && sig[2] == 0 && sig[3] == 0;
		}
		std::fclose(fp);
		return ok;
	}

	Unavailable Reason()
	{
		if (!s_attached.load(std::memory_order_acquire))
			return Unavailable::NotVulkan;
		if (s_adreno_generation < 6)
			return Unavailable::GpuUnsupported;
		if (!s_dll_set.load(std::memory_order_relaxed))
			return Unavailable::NoDll;
		if (!s_dll_ok.load(std::memory_order_relaxed))
			return Unavailable::DllUnreadable;
		if (s_init_failed.load(std::memory_order_relaxed))
			return Unavailable::InitFailed;
		return Unavailable::Available;
	}

	/// Frame generation itself: the device can run the shaders and the settings ask for it.
	bool Generating()
	{
		return GSConfig.LsfgEnabled && Reason() == Unavailable::Available && s_frame_gen.has_value();
	}

	void TearDownGeneration()
	{
		if (s_vk_device != VK_NULL_HANDLE)
			vkDeviceWaitIdle(s_vk_device);
		s_frame_gen.reset();
	}

	/// Frame generation exists whenever it is switched on and can run.
	void EnsureFrameGen()
	{
		if (GSConfig.LsfgEnabled && !s_frame_gen && s_attached.load() && Reason() == Unavailable::Available)
			s_frame_gen.emplace(*s_allocator, &*s_gs_device);
	}

	/// What armflash_lsfg_present() does, in ARMSX2's PresentWithGeneration order.
	s32 Present(VkImage image, VkExtent2D extent, VkFormat format, float content_rate, float display_rate)
	{
		(void)format;
		if (s_surface == VK_NULL_HANDLE || image == VK_NULL_HANDLE || extent.width == 0 || extent.height == 0)
			return 0;

		const u32 multiplier = std::clamp<u32>(GSConfig.LsfgMultiplier, 2, 4);
		// Rebuild the swap chain BEFORE anything is acquired from it: when it went out of date or
		// suboptimal (a resize, a rotation), and when the multiplier wants another image count.
		if (s_swapchain == VK_NULL_HANDLE || s_swap_stale || s_swap_built_for != multiplier)
		{
			vkDeviceWaitIdle(s_vk_device);
			if (!BuildSwapchain(extent, multiplier))
				return -1;
		}
		// Work images follow the rendered frame's size and the multiplier.
		if (!s_resources || s_capture_extent.width != extent.width || s_capture_extent.height != extent.height ||
			s_resources_mult != multiplier)
		{
			vkDeviceWaitIdle(s_vk_device);
			// The passes key their descriptors on the generation images' view handles, and new
			// views can reuse an old handle's value: start them again with the images.
			s_frame_gen.reset();
			DestroyResources();
			if (!CreateResources(extent, multiplier))
			{
				Console.Error("LSFG: failed to create frame-generation resources.");
				DestroyResources();
				s_init_failed.store(true, std::memory_order_relaxed);
				SetFailure("could not create its images");
				return -1;
			}
		}
		EnsureFrameGen();

		// ★ Take this frame's slot and WAIT for the GPU to be done with it before touching anything
		// in it: resetting a command buffer still executing, or resubmitting a pending one, is
		// undefined, and was reliably fatal in ARMSX2 on the first frame that generated.
		const u32 slot_index = static_cast<u32>(s_frame_index % kSlots);
		FrameSlot& slot = s_slots[slot_index];
		if (slot.submitted)
		{
			static constexpr u64 kSlotTimeoutNs = 500ull * 1000 * 1000;
			if (vkWaitForFences(s_vk_device, 1, &slot.fence, VK_TRUE, kSlotTimeoutNs) != VK_SUCCESS)
				return 0;
		}
		vkResetFences(s_vk_device, 1, &slot.fence);
		slot.submitted = false;
		if (slot.timed)
		{
			u64 ticks[2] = {};
			if (vkGetQueryPoolResults(s_vk_device, s_query_pool, slot_index * 2, 2, sizeof(ticks), ticks, sizeof(u64),
					VK_QUERY_RESULT_64_BIT) == VK_SUCCESS)
			{
				s_gpu_ns_sum += static_cast<double>((ticks[1] - ticks[0]) & s_tick_mask) * s_ns_per_tick;
				s_gpu_samples++;
			}
			slot.timed = false;
		}

		// The real frame's image first: it is the one that must go out. Generated frames are a
		// bonus and get the images left over.
		static constexpr u64 kRealAcquireTimeoutNs = 250ull * 1000 * 1000;
		u32 real_index = 0;
		const VkResult acq = AcquireImage(kRealAcquireTimeoutNs, &real_index);
		if (acq == VK_ERROR_OUT_OF_DATE_KHR)
		{
			s_swap_stale = true;
			return 0;
		}
		if (acq == VK_ERROR_SURFACE_LOST_KHR || acq == VK_ERROR_DEVICE_LOST)
			return -1;
		if (acq != VK_SUCCESS)
			return 0; // nothing free in time: this frame is skipped, the next one tries again

		const VkCommandBufferBeginInfo begin = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr,
			VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, nullptr};
		vkResetCommandBuffer(slot.cmd, 0);
		vkBeginCommandBuffer(slot.cmd, &begin);
		if (s_query_pool != VK_NULL_HANDLE)
		{
			vkCmdResetQueryPool(slot.cmd, s_query_pool, slot_index * 2, 2);
			vkCmdWriteTimestamp(slot.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, s_query_pool, slot_index * 2);
		}

		// 1. The rendered frame into s_capture (ARMSX2's CaptureGameFrame). Ruffle's render pass
		//    left it in COLOR_ATTACHMENT_OPTIMAL, in a submission earlier on this queue: a barrier's
		//    first scope covers everything earlier in submission order, so no semaphore is needed.
		//    It goes back to that layout, which is what wgpu's tracking believes it is in, and the
		//    barrier back orders Ruffle's next frame after this read.
		TransitionImage(slot.cmd, image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
			VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
		TransitionImage(slot.cmd, *s_capture.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
			VK_ACCESS_TRANSFER_WRITE_BIT);
		{
			VkImageBlit blit = {};
			blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
			blit.srcOffsets[1] = {static_cast<s32>(extent.width), static_cast<s32>(extent.height), 1};
			blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
			blit.dstOffsets[1] = {static_cast<s32>(s_capture_extent.width), static_cast<s32>(s_capture_extent.height), 1};
			vkCmdBlitImage(slot.cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, *s_capture.image,
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
		}
		TransitionImage(slot.cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
			VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
		// GENERAL, which frame generation's copy at the start of its passes expects.
		TransitionImage(slot.cmd, *s_capture.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
			VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT);

		// 2. How many frames the screen has room for between two of the game's. A 60 fps game on a
		//    60 Hz screen has none, and its frames are not even processed.
		size_t room = kMaxGenerations;
		if (content_rate > 0.5f && display_rate > 0.5f)
		{
			const float ratio = display_rate / content_rate;
			room = ratio < 1.0f ? 0 : static_cast<size_t>(std::floor(ratio + 0.05f)) - 1;
		}
		const size_t budget = s_acquirable > 1 ? s_acquirable - 1 : 0;
		const size_t capacity = std::min({room, budget, static_cast<size_t>(multiplier - 1)});

		size_t generations = 0;
		const bool generating = Generating();
		if (generating && capacity > 0)
		{
			s_frame_gen->Process(*s_device, Vulkan::vk::CommandBuffer{slot.cmd}, *s_capture.image, slot.gen[0].view,
				s_capture_extent, kWorkFormat, s_capture_extent);
			s_fp16_active.store(s_frame_gen->UsingFp16(), std::memory_order_relaxed);
			// Frame generation turned itself off: its shaders could not be loaded or built. Say why,
			// once, in the log and on the overlay.
			if (s_frame_gen->IsUnavailable() && !s_no_shaders.load(std::memory_order_relaxed))
			{
				const char* why = s_frame_gen->UnavailableReason();
				Console.Error("LSFG: frame generation turned off: %s.", why);
				SetFailure(why);
				s_no_shaders.store(true, std::memory_order_relaxed);
				s_init_failed.store(true, std::memory_order_relaxed);
			}
			// WantedGenerations drives the PACER: with adaptive pacing the count varies to hold the
			// shown rate steady rather than multiplying whatever arrived.
			const size_t wanted = s_frame_gen->WantedGenerations(capacity);
			generations = std::min(wanted, s_frame_gen->GeneratedFrameCount());
		}
		else if (s_frame_gen)
		{
			// Keeps the pacer honest: the next frame that has room starts from a fresh history
			// instead of interpolating across the frames skipped here.
			(void)s_frame_gen->WantedGenerations(0);
		}

		// 3. Generated frames into images of ours, then into further acquired swap chain images.
		static constexpr u64 kGeneratedAcquireTimeoutNs = 50ull * 1000 * 1000;
		u32 gen_index[kMaxGenerations] = {};
		size_t acquired = 0;
		for (size_t i = 0; i < generations && i < kMaxGenerations; i++)
		{
			u32 index = 0;
			if (AcquireImage(kGeneratedAcquireTimeoutNs, &index) != VK_SUCCESS)
				break; // nothing free in time: the real frame still goes out
			WorkImage& gen = slot.gen[i];
			s_frame_gen->GenerateInto(*s_device, Vulkan::vk::CommandBuffer{slot.cmd}, *gen.image, gen.view, i);
			// The pass leaves our image in GENERAL.
			TransitionImage(slot.cmd, *gen.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
			CopyToSwapImage(slot.cmd, *gen.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, s_capture_extent, index);
			gen_index[acquired++] = index;
		}

		// 4. The real frame.
		CopyToSwapImage(slot.cmd, *s_capture.image, VK_IMAGE_LAYOUT_GENERAL, s_capture_extent, real_index);

		if (s_query_pool != VK_NULL_HANDLE)
			vkCmdWriteTimestamp(slot.cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, s_query_pool, slot_index * 2 + 1);
		vkEndCommandBuffer(slot.cmd);

		// 5. ONE submit, signalling one semaphore per present that follows. (A submit per frame
		//    walks into the binary-semaphore trap ARMSX2 hit: a binary semaphore is waited once.)
		VkSemaphore signals[kMaxGenerations + 1] = {};
		u32 signal_count = 0;
		for (size_t i = 0; i < acquired; i++)
			signals[signal_count++] = s_present_sems[gen_index[i]];
		signals[signal_count++] = s_present_sems[real_index];
		VkSubmitInfo submit = {};
		submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
		submit.commandBufferCount = 1;
		submit.pCommandBuffers = &slot.cmd;
		submit.signalSemaphoreCount = signal_count;
		submit.pSignalSemaphores = signals;
		const VkResult sub = vkQueueSubmit(s_gs_device->GetGraphicsQueue(), 1, &submit, slot.fence);
		if (sub != VK_SUCCESS)
		{
			Console.Error("LSFG: vkQueueSubmit() failed: %d", static_cast<int>(sub));
			return -1;
		}
		slot.submitted = true;
		slot.timed = s_query_pool != VK_NULL_HANDLE;
		s_frame_index++;

		// 6. Generated frames first: they sit between the previous real frame and this one.
		//    Presents on one queue are processed in call order.
		u32 presented_generated = 0;
		for (size_t i = 0; i < acquired; i++)
		{
			const VkPresentInfoKHR present = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, nullptr, 1,
				&s_present_sems[gen_index[i]], 1, &s_swapchain, &gen_index[i], nullptr};
			// ★ VK_SUBOPTIMAL_KHR IS A SUCCESS CODE: the frame was presented.
			const VkResult pres = vkQueuePresentKHR(s_gs_device->GetGraphicsQueue(), &present);
			if (pres == VK_SUBOPTIMAL_KHR)
				s_swap_stale = true;
			else if (pres != VK_SUCCESS)
			{
				s_swap_stale = true;
				continue;
			}
			presented_generated++;
		}
		const VkPresentInfoKHR present = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, nullptr, 1,
			&s_present_sems[real_index], 1, &s_swapchain, &real_index, nullptr};
		const VkResult pres = vkQueuePresentKHR(s_gs_device->GetGraphicsQueue(), &present);
		if (pres == VK_SUBOPTIMAL_KHR || pres == VK_ERROR_OUT_OF_DATE_KHR)
			s_swap_stale = true;
		else if (pres == VK_ERROR_SURFACE_LOST_KHR || pres == VK_ERROR_DEVICE_LOST)
			return -1;
		NoteFramesDisplayed(1, presented_generated);
		return 1;
	}

	void CopyString(std::string& out, const char* in)
	{
		out = in ? in : "";
	}
} // namespace

extern "C" {

__attribute__((visibility("default"))) uint32_t armflash_lsfg_abi_version(void)
{
	return ARMFLASH_LSFG_ABI_VERSION;
}

__attribute__((visibility("default"))) int32_t armflash_lsfg_attach(const ArmflashLsfgDevice* device)
{
	try
	{
		if (!device || device->abi != ARMFLASH_LSFG_ABI_VERSION || !device->get_instance_proc_addr)
			return -1;
		if (s_attached.load())
			armflash_lsfg_detach();
		s_gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(device->get_instance_proc_addr);
		const VkInstance instance = reinterpret_cast<VkInstance>(device->instance);
		const VkDevice vk_device = reinterpret_cast<VkDevice>(device->device);
		const char* missing = nullptr;
		if (!ArmflashVk::Load(s_gipa, instance, vk_device, &missing))
		{
			Console.Error("LSFG: the player's Vulkan has no %s.", missing ? missing : "?");
			return -2;
		}
		GSDeviceVK::OptionalExtensions ext;
		ext.vk_khr_vulkan_memory_model = (device->enabled_features & ARMFLASH_LSFG_FEATURE_MEMORY_MODEL) != 0;
		ext.vk_ext_robustness2_null_descriptor = (device->enabled_features & ARMFLASH_LSFG_FEATURE_NULL_DESCRIPTOR) != 0;
		ext.vk_khr_shader_float16_int8 = (device->enabled_features & ARMFLASH_LSFG_FEATURE_FLOAT16) != 0;
		s_gs_device.emplace(instance, reinterpret_cast<VkPhysicalDevice>(device->physical_device), vk_device,
			reinterpret_cast<VkQueue>(device->queue), device->queue_family, ext);
		GSDeviceVK::SetInstance(&*s_gs_device);
		s_vk_device = vk_device;
		s_adreno_generation = AdrenoGeneration(s_gs_device->GetDeviceProperties());

		const VkFenceCreateInfo fence_ci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0};
		if (vkCreateFence(s_vk_device, &fence_ci, nullptr, &s_acquire_fence) != VK_SUCCESS)
			return -3;

		s_device.emplace(&*s_gs_device);
		s_allocator.emplace(&*s_gs_device);
		s_init_failed.store(false);
		s_no_shaders.store(false);
		SetFailure("");
		// ★ Both features are hard requirements of the shaders; checked before anything is built.
		if (!s_device->IsVulkanMemoryModelSupported() || !s_device->HasNullDescriptor())
		{
			Console.Warning("LSFG: the device lacks the Vulkan memory model or nullDescriptor; frame generation unavailable.");
			s_init_failed.store(true);
			SetFailure("the GPU lacks the Vulkan memory model or null descriptors");
		}
		s_attached.store(true, std::memory_order_release);
		Console.WriteLn("LSFG: attached to %s (Adreno generation %u, features %u).",
			s_gs_device->GetDeviceProperties().deviceName, s_adreno_generation, device->enabled_features);
		return 0;
	}
	catch (...)
	{
		return -4;
	}
}

__attribute__((visibility("default"))) void armflash_lsfg_detach(void)
{
	try
	{
		if (!s_attached.load())
			return;
		armflash_lsfg_release_window();
		if (s_vk_device != VK_NULL_HANDLE)
			vkDeviceWaitIdle(s_vk_device);
		s_frame_gen.reset();
		DestroyResources();
		if (s_acquire_fence != VK_NULL_HANDLE)
			vkDestroyFence(s_vk_device, s_acquire_fence, nullptr);
		s_acquire_fence = VK_NULL_HANDLE;
		s_allocator.reset();
		s_device.reset();
		GSDeviceVK::SetInstance(nullptr);
		s_gs_device.reset();
		s_vk_device = VK_NULL_HANDLE;
		s_attached.store(false);
		s_display_fps.store(0.0f);
	}
	catch (...)
	{
	}
}

__attribute__((visibility("default"))) void armflash_lsfg_configure(const ArmflashLsfgSettings* settings)
{
	try
	{
		if (!settings || settings->abi != ARMFLASH_LSFG_ABI_VERSION)
			return;
		const bool was_enabled = GSConfig.LsfgEnabled;
		const bool was_fp16 = GSConfig.LsfgFp16;
		const u8 was_flow = GSConfig.LsfgFlowScale;
		const std::string was_dll = GSConfig.LsfgDllPath;

		GSConfig.LsfgEnabled = settings->enabled != 0;
		GSConfig.LsfgMultiplier = std::clamp<u32>(settings->multiplier, 2, 4);
		GSConfig.LsfgFlowScale = static_cast<u8>(std::clamp<u32>(settings->flow_scale, 25, 100));
		GSConfig.LsfgFp16 = settings->fp16 != 0;
		GSConfig.LsfgTargetRate = std::min<u32>(settings->target_rate, 480);
		CopyString(GSConfig.LsfgDllPath, settings->dll_path);
		CopyString(GSConfig.CacheDir, settings->cache_dir);
		s_enabled.store(GSConfig.LsfgEnabled);

		const bool dll_changed = was_dll != GSConfig.LsfgDllPath;
		if (dll_changed || !s_dll_set.load())
		{
			s_dll_set.store(!GSConfig.LsfgDllPath.empty());
			s_dll_ok.store(!GSConfig.LsfgDllPath.empty() && LooksLikeLosslessDll(GSConfig.LsfgDllPath.c_str()));
			if (dll_changed && s_no_shaders.load())
			{
				// A new DLL deserves a fresh attempt; the previous failure may have been this file.
				s_no_shaders.store(false);
				s_init_failed.store(false);
				SetFailure("");
			}
		}

		// The shaders are chosen when frame generation starts (fp16 or not, from which DLL), so
		// a change to either starts it again. Off releases it, with the GPU idle first: its
		// resources are destroyed at once, not deferred.
		const bool restart = dll_changed || was_fp16 != GSConfig.LsfgFp16 || !GSConfig.LsfgEnabled;
		if (s_frame_gen && restart)
			TearDownGeneration();
		EnsureFrameGen();
		if (was_enabled != GSConfig.LsfgEnabled || was_flow != GSConfig.LsfgFlowScale)
		{
			Console.WriteLn("LSFG: %s, x%u, motion %s, %s precision, pacing %s.", GSConfig.LsfgEnabled ? "on" : "off",
				GSConfig.LsfgMultiplier, GSConfig.LsfgFlowScale >= 100 ? "auto" : "fixed", GSConfig.LsfgFp16 ? "half" : "full",
				GSConfig.LsfgTargetRate ? "adaptive" : "fixed");
		}
	}
	catch (...)
	{
		s_init_failed.store(true);
	}
}

__attribute__((visibility("default"))) int32_t armflash_lsfg_unavailable_reason(void)
{
	return static_cast<int32_t>(Reason());
}

__attribute__((visibility("default"))) int32_t armflash_lsfg_set_window(void* window)
{
	try
	{
		if (!s_attached.load() || !window)
			return -1;
		armflash_lsfg_release_window();
		const VkAndroidSurfaceCreateInfoKHR ci = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, nullptr, 0,
			static_cast<ANativeWindow*>(window)};
		const VkResult res = vkCreateAndroidSurfaceKHR(s_gs_device->GetVkInstance(), &ci, nullptr, &s_surface);
		if (res != VK_SUCCESS)
		{
			// VK_ERROR_NATIVE_WINDOW_IN_USE_KHR: the player's own surface is still on it.
			Console.Error("LSFG: vkCreateAndroidSurfaceKHR() failed: %d", static_cast<int>(res));
			s_surface = VK_NULL_HANDLE;
			return -2;
		}
		s_window = static_cast<ANativeWindow*>(window);
		ANativeWindow_acquire(s_window);
		s_swap_stale = true;
		return 0;
	}
	catch (...)
	{
		return -3;
	}
}

__attribute__((visibility("default"))) void armflash_lsfg_release_window(void)
{
	try
	{
		if (s_vk_device != VK_NULL_HANDLE)
			vkDeviceWaitIdle(s_vk_device);
		// Slots are idle now; the next frame starts a fresh history.
		for (FrameSlot& slot : s_slots)
			slot.submitted = false;
		if (s_vk_device != VK_NULL_HANDLE)
			DestroySwapchain();
		if (s_surface != VK_NULL_HANDLE && s_gs_device)
			vkDestroySurfaceKHR(s_gs_device->GetVkInstance(), s_surface, nullptr);
		s_surface = VK_NULL_HANDLE;
		if (s_window)
			ANativeWindow_release(s_window);
		s_window = nullptr;
		s_display_fps.store(0.0f);
		s_fps_window_open = false;
		s_fps_real = s_fps_generated = 0;
	}
	catch (...)
	{
	}
}

__attribute__((visibility("default"))) int32_t armflash_lsfg_present(uint64_t image, uint32_t width, uint32_t height,
	uint32_t format, float content_rate, float display_rate)
{
	try
	{
		if (!s_attached.load())
			return -1;
		return Present(reinterpret_cast<VkImage>(image), {width, height}, static_cast<VkFormat>(format), content_rate, display_rate);
	}
	catch (...)
	{
		return -1;
	}
}

__attribute__((visibility("default"))) void armflash_lsfg_idle(void)
{
	if (s_vk_device != VK_NULL_HANDLE)
		vkDeviceWaitIdle(s_vk_device);
	for (FrameSlot& slot : s_slots)
		slot.submitted = false;
}

__attribute__((visibility("default"))) uint32_t armflash_lsfg_status(char* out, uint32_t capacity)
{
	try
	{
		std::string text;
		if (s_enabled.load(std::memory_order_relaxed))
		{
			// Every state says something while it is switched on: "on but silent" looks exactly
			// like "on and broken".
			switch (Reason())
			{
				case Unavailable::Available:
				{
					const float fps = s_display_fps.load(std::memory_order_relaxed);
					if (fps <= 0.0f)
						text = "LSFG: starting";
					else
					{
						char buf[96];
						std::snprintf(buf, sizeof(buf), "LSFG: %.2f", fps);
						text = buf;
						if (s_fp16_active.load(std::memory_order_relaxed))
							text += " fp16";
						if (const float gpu_ms = s_gpu_ms.load(std::memory_order_relaxed); gpu_ms > 0.0f)
						{
							std::snprintf(buf, sizeof(buf), " · %.2f ms", gpu_ms);
							text += buf;
						}
					}
					break;
				}
				case Unavailable::InitFailed:
				{
					std::unique_lock lock(s_failure_mutex);
					text = s_no_shaders.load() ? "LSFG: no shaders" : "LSFG: failed";
					if (!s_failure_reason.empty())
						text += " (" + s_failure_reason + ")";
					break;
				}
				default:
					text = "LSFG: unavailable";
					break;
			}
		}
		if (out && capacity > 0)
		{
			const size_t n = std::min<size_t>(text.size(), capacity - 1);
			std::memcpy(out, text.data(), n);
			out[n] = '\0';
		}
		return static_cast<uint32_t>(text.size());
	}
	catch (...)
	{
		if (out && capacity > 0)
			out[0] = '\0';
		return 0;
	}
}

__attribute__((visibility("default"))) float armflash_lsfg_display_fps(void)
{
	return s_display_fps.load(std::memory_order_relaxed);
}

__attribute__((visibility("default"))) int32_t armflash_lsfg_check_dll(const char* path)
{
	return LooksLikeLosslessDll(path) ? 1 : 0;
}

} // extern "C"
