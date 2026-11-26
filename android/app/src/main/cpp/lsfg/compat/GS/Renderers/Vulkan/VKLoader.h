// ARMFlash stand-in for PCSX2's GS/Renderers/Vulkan/VKLoader.h.
//
// The ported code calls Vulkan by its plain names (vkCmdDispatch, ...). Here those names are
// function pointers defined in this library and loaded from the vkGetInstanceProcAddr the player's
// wgpu instance was made with, so a custom driver (Turnip through adrenotools) is called directly
// and never through the system loader, which does not know its handles.
//
// They are hidden: libarmflash_lsfg.so exports only its armflash_lsfg_* C entry points (see
// armflash_lsfg.map), so these never meet libvulkan's own exported vk* functions. That is the
// lesson of ARMSX2's volk collision (two definitions of the same 759 names).
#pragma once

#define VK_NO_PROTOTYPES
// For vkCreateAndroidSurfaceKHR (ARMSX2 had to set this on volk too).
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif
#include <vulkan/vulkan.h>

// Instance-level entry points.
#define ARMFLASH_VK_INSTANCE_FUNCTIONS(X) \
	X(vkGetDeviceProcAddr) \
	X(vkGetPhysicalDeviceProperties) \
	X(vkGetPhysicalDeviceMemoryProperties) \
	X(vkGetPhysicalDeviceFormatProperties) \
	X(vkGetPhysicalDeviceQueueFamilyProperties) \
	X(vkCreateAndroidSurfaceKHR) \
	X(vkDestroySurfaceKHR) \
	X(vkGetPhysicalDeviceSurfaceSupportKHR) \
	X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR) \
	X(vkGetPhysicalDeviceSurfaceFormatsKHR) \
	X(vkGetPhysicalDeviceSurfacePresentModesKHR)

// Device-level entry points.
#define ARMFLASH_VK_DEVICE_FUNCTIONS(X) \
	X(vkDeviceWaitIdle) \
	X(vkQueueSubmit) \
	X(vkQueueWaitIdle) \
	X(vkCreateImage) \
	X(vkDestroyImage) \
	X(vkCreateBuffer) \
	X(vkDestroyBuffer) \
	X(vkGetImageMemoryRequirements) \
	X(vkGetBufferMemoryRequirements) \
	X(vkAllocateMemory) \
	X(vkFreeMemory) \
	X(vkBindImageMemory) \
	X(vkBindBufferMemory) \
	X(vkMapMemory) \
	X(vkUnmapMemory) \
	X(vkFlushMappedMemoryRanges) \
	X(vkCreateImageView) \
	X(vkDestroyImageView) \
	X(vkCreateSampler) \
	X(vkDestroySampler) \
	X(vkCreateShaderModule) \
	X(vkDestroyShaderModule) \
	X(vkCreateDescriptorSetLayout) \
	X(vkDestroyDescriptorSetLayout) \
	X(vkCreatePipelineLayout) \
	X(vkDestroyPipelineLayout) \
	X(vkCreateComputePipelines) \
	X(vkDestroyPipeline) \
	X(vkCreateDescriptorPool) \
	X(vkDestroyDescriptorPool) \
	X(vkResetDescriptorPool) \
	X(vkAllocateDescriptorSets) \
	X(vkFreeDescriptorSets) \
	X(vkUpdateDescriptorSets) \
	X(vkCreateCommandPool) \
	X(vkDestroyCommandPool) \
	X(vkAllocateCommandBuffers) \
	X(vkResetCommandBuffer) \
	X(vkBeginCommandBuffer) \
	X(vkEndCommandBuffer) \
	X(vkCreateFence) \
	X(vkDestroyFence) \
	X(vkWaitForFences) \
	X(vkResetFences) \
	X(vkGetFenceStatus) \
	X(vkCreateSemaphore) \
	X(vkDestroySemaphore) \
	X(vkCreateQueryPool) \
	X(vkDestroyQueryPool) \
	X(vkGetQueryPoolResults) \
	X(vkCmdResetQueryPool) \
	X(vkCmdWriteTimestamp) \
	X(vkCmdPipelineBarrier) \
	X(vkCmdCopyImage) \
	X(vkCmdBlitImage) \
	X(vkCmdClearColorImage) \
	X(vkCmdBindPipeline) \
	X(vkCmdBindDescriptorSets) \
	X(vkCmdDispatch) \
	X(vkCreateSwapchainKHR) \
	X(vkDestroySwapchainKHR) \
	X(vkGetSwapchainImagesKHR) \
	X(vkAcquireNextImageKHR) \
	X(vkQueuePresentKHR)

#define ARMFLASH_VK_DECLARE(name) extern PFN_##name name;
ARMFLASH_VK_INSTANCE_FUNCTIONS(ARMFLASH_VK_DECLARE)
ARMFLASH_VK_DEVICE_FUNCTIONS(ARMFLASH_VK_DECLARE)
#undef ARMFLASH_VK_DECLARE

namespace ArmflashVk
{
	/// Loads every entry point above from [gipa] for [instance] and [device]. False, naming the
	/// first one missing in [missing], when any is unavailable (the surface and swap chain ones
	/// are always there: the player's instance and device were made to present).
	bool Load(PFN_vkGetInstanceProcAddr gipa, VkInstance instance, VkDevice device, const char** missing);
} // namespace ArmflashVk
