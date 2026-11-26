// ARMFlash stand-in for PCSX2's GSDeviceVK.
//
// The ported frame-generation code asks "the GS device" for its VkDevice, for what optional
// features it was created with, and to wait for the GPU. In ARMFlash that device is the native
// Flash player's (Ruffle on wgpu): fp-ruffle hands its raw handles to armflash_lsfg_attach(), and
// frame generation records ordinary compute on it, submitted on wgpu's own queue from the
// player's render thread, the only thread that uses that queue.
#pragma once

#include "common/Pcsx2Types.h"
#include "GS/Renderers/Vulkan/VKLoader.h"

class GSDeviceVK
{
public:
	/// What the device was CREATED with (wgpu-hal's ARMFLASH_FRAME_GENERATION_ENABLED), never what
	/// the driver merely lists. Declaring the memory model in a shader, or binding a null
	/// descriptor, on a device created without them is invalid usage.
	struct OptionalExtensions
	{
		bool vk_khr_vulkan_memory_model = false;
		bool vk_ext_robustness2_null_descriptor = false;
		bool vk_khr_shader_float16_int8 = false;
	};

	GSDeviceVK(VkInstance instance, VkPhysicalDevice physical_device, VkDevice device, VkQueue queue,
		u32 queue_family, const OptionalExtensions& extensions);

	/// The attached device, or null when none is.
	static GSDeviceVK* GetInstance();
	static void SetInstance(GSDeviceVK* device);

	VkInstance GetVkInstance() const { return m_instance; }
	VkPhysicalDevice GetPhysicalDevice() const { return m_physical_device; }
	VkDevice GetDevice() const { return m_device; }
	VkQueue GetGraphicsQueue() const { return m_queue; }
	u32 GetGraphicsQueueFamilyIndex() const { return m_queue_family; }
	const OptionalExtensions& GetOptionalExtensions() const { return m_extensions; }
	const VkPhysicalDeviceProperties& GetDeviceProperties() const { return m_properties; }
	const VkPhysicalDeviceMemoryProperties& GetMemoryProperties() const { return m_memory; }

	/// Index of a memory type [type_bits] allows with all of [wanted], or -1.
	s32 FindMemoryType(u32 type_bits, VkMemoryPropertyFlags wanted) const;

	/// Every queue of the device idle. Only ever called from the render thread, which owns them.
	void WaitForGPUIdle() const { vkDeviceWaitIdle(m_device); }

private:
	VkInstance m_instance;
	VkPhysicalDevice m_physical_device;
	VkDevice m_device;
	VkQueue m_queue;
	u32 m_queue_family;
	OptionalExtensions m_extensions;
	VkPhysicalDeviceProperties m_properties = {};
	VkPhysicalDeviceMemoryProperties m_memory = {};
};
