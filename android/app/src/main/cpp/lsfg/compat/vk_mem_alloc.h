// ARMFlash stand-in for the Vulkan Memory Allocator, with only the five calls the ported code
// makes (LsfgVkCompat.cpp): one vkAllocateMemory per image or buffer. Frame generation makes a
// few dozen images once, when it starts or the screen size changes, so pooling buys nothing, and
// this keeps LsfgVkCompat as ARMSX2 has it.
#pragma once

#include "GS/Renderers/Vulkan/VKLoader.h"

class GSDeviceVK;

/// The device allocations come from.
typedef GSDeviceVK* VmaAllocator;

struct VmaAllocation_T
{
	VkDeviceMemory memory = VK_NULL_HANDLE;
	void* mapped = nullptr;
	bool coherent = true;
};
typedef VmaAllocation_T* VmaAllocation;

enum VmaMemoryUsage
{
	VMA_MEMORY_USAGE_GPU_ONLY = 1,
	VMA_MEMORY_USAGE_CPU_TO_GPU = 3,
	VMA_MEMORY_USAGE_GPU_TO_CPU = 4,
};

enum VmaAllocationCreateFlagBits
{
	VMA_ALLOCATION_CREATE_MAPPED_BIT = 0x4,
	VMA_ALLOCATION_CREATE_WITHIN_BUDGET_BIT = 0x100,
};

struct VmaAllocationCreateInfo
{
	VkFlags flags = 0;
	VmaMemoryUsage usage = VMA_MEMORY_USAGE_GPU_ONLY;
	VkMemoryPropertyFlags requiredFlags = 0;
};

struct VmaAllocationInfo
{
	void* pMappedData = nullptr;
};

VkResult vmaCreateImage(VmaAllocator allocator, const VkImageCreateInfo* image_ci,
	const VmaAllocationCreateInfo* alloc_ci, VkImage* image, VmaAllocation* allocation, VmaAllocationInfo* info);
void vmaDestroyImage(VmaAllocator allocator, VkImage image, VmaAllocation allocation);
VkResult vmaCreateBuffer(VmaAllocator allocator, const VkBufferCreateInfo* buffer_ci,
	const VmaAllocationCreateInfo* alloc_ci, VkBuffer* buffer, VmaAllocation* allocation, VmaAllocationInfo* info);
void vmaDestroyBuffer(VmaAllocator allocator, VkBuffer buffer, VmaAllocation allocation);
VkResult vmaFlushAllocation(VmaAllocator allocator, VmaAllocation allocation, VkDeviceSize offset, VkDeviceSize size);
