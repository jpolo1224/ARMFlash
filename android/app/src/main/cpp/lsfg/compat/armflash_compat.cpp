// SPDX-License-Identifier: GPL-3.0-or-later
//
// ARMFlash: what the compat/ headers declare. See compat/README.md.

#include "Config.h"
#include "common/Console.h"
#include "common/FileSystem.h"
#include "common/Path.h"
#include "GS/GSCacheFile.h"
#include "GS/GSXXH.h"
#include "GS/Renderers/Vulkan/GSDeviceVK.h"
#include "GS/Renderers/Vulkan/VKLoader.h"
#include "vk_mem_alloc.h"

#include <android/log.h>

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

ArmflashLsfgConfig GSConfig;

// --- Console ---------------------------------------------------------------------------------

namespace
{
	constexpr const char* kTag = "ARMFlashLSFG";

	void Log(int priority, const char* format, va_list args)
	{
		__android_log_vprint(priority, kTag, format, args);
	}
} // namespace

const ArmflashConsole Console;

void ArmflashConsole::WriteLn(const char* format, ...) const
{
	va_list args;
	va_start(args, format);
	Log(ANDROID_LOG_INFO, format, args);
	va_end(args);
}

void ArmflashConsole::Warning(const char* format, ...) const
{
	va_list args;
	va_start(args, format);
	Log(ANDROID_LOG_WARN, format, args);
	va_end(args);
}

void ArmflashConsole::Error(const char* format, ...) const
{
	va_list args;
	va_start(args, format);
	Log(ANDROID_LOG_ERROR, format, args);
	va_end(args);
}

// --- FileSystem / Path -------------------------------------------------------------------------

bool FileSystem::StatFile(const char* path, FILESYSTEM_STAT_DATA* sd)
{
	struct stat st = {};
	if (!path || stat(path, &st) != 0)
		return false;
	sd->Size = static_cast<s64>(st.st_size);
	sd->ModificationTime = static_cast<s64>(st.st_mtime);
	return true;
}

bool FileSystem::FileExists(const char* path)
{
	struct stat st = {};
	return path && stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

bool FileSystem::DeleteFilePath(const char* path)
{
	return path && unlink(path) == 0;
}

std::optional<std::vector<u8>> FileSystem::ReadBinaryFile(const char* path)
{
	FILE* fp = path ? std::fopen(path, "rb") : nullptr;
	if (!fp)
		return std::nullopt;
	std::vector<u8> data;
	u8 buffer[64 * 1024];
	for (;;)
	{
		const size_t n = std::fread(buffer, 1, sizeof(buffer), fp);
		data.insert(data.end(), buffer, buffer + n);
		if (n < sizeof(buffer))
			break;
	}
	const bool failed = std::ferror(fp) != 0;
	std::fclose(fp);
	if (failed)
		return std::nullopt;
	return data;
}

std::string Path::Combine(std::string_view base, std::string_view next)
{
	std::string out(base);
	if (!out.empty() && out.back() != '/')
		out += '/';
	out += next;
	return out;
}

// --- GSCacheFile -------------------------------------------------------------------------------

namespace
{
	struct FramedHeader
	{
		u32 magic;
		u32 kind;
		u64 stamp;
		u64 checksum;
		u64 size;
	};
	constexpr u32 kFramedMagic = 0x46524D41; // "AMRF"
} // namespace

void GSCacheFile::Stamp::Add(std::string_view key, std::string_view value)
{
	m_text.append(key).append("=").append(value).append(";");
}

u64 GSCacheFile::Stamp::GetDigest() const
{
	return GSXXH3_64bits(m_text.data(), m_text.size());
}

std::string_view GSCacheFile::GetBuildId()
{
	// Raise the number whenever the way shaders are read out of the DLL or adopted changes
	// (LosslessDll.cpp, LsfgTranslate.cpp), so an old cache is made again instead of trusted.
	return "armflash-lsfg-1";
}

bool GSCacheFile::WriteFramedFile(const std::string& path, u32 kind, const Stamp& stamp, const void* data, size_t size)
{
	const std::string temp = path + ".tmp";
	FILE* fp = std::fopen(temp.c_str(), "wb");
	if (!fp)
		return false;
	const FramedHeader header = {kFramedMagic, kind, stamp.GetDigest(), GSXXH3_64bits(data, size), static_cast<u64>(size)};
	const bool ok = std::fwrite(&header, sizeof(header), 1, fp) == 1 && (size == 0 || std::fwrite(data, size, 1, fp) == 1);
	const bool closed = std::fclose(fp) == 0;
	if (!ok || !closed || std::rename(temp.c_str(), path.c_str()) != 0)
	{
		unlink(temp.c_str());
		return false;
	}
	return true;
}

GSCacheFile::ReadResult GSCacheFile::ReadFramedFile(const std::string& path, u32 kind, const Stamp& stamp, std::vector<u8>* out)
{
	std::optional<std::vector<u8>> file = FileSystem::ReadBinaryFile(path.c_str());
	if (!file)
		return ReadResult::Missing;
	if (file->size() < sizeof(FramedHeader))
		return ReadResult::Corrupt;
	FramedHeader header;
	std::memcpy(&header, file->data(), sizeof(header));
	if (header.magic != kFramedMagic || header.kind != kind || header.stamp != stamp.GetDigest())
		return ReadResult::Mismatch;
	if (header.size != file->size() - sizeof(header))
		return ReadResult::Corrupt;
	const u8* payload = file->data() + sizeof(header);
	if (GSXXH3_64bits(payload, header.size) != header.checksum)
		return ReadResult::Corrupt;
	out->assign(payload, payload + header.size);
	return ReadResult::Ok;
}

// --- Vulkan entry points -----------------------------------------------------------------------

#define ARMFLASH_VK_DEFINE(name) PFN_##name name = nullptr;
ARMFLASH_VK_INSTANCE_FUNCTIONS(ARMFLASH_VK_DEFINE)
ARMFLASH_VK_DEVICE_FUNCTIONS(ARMFLASH_VK_DEFINE)
#undef ARMFLASH_VK_DEFINE

bool ArmflashVk::Load(PFN_vkGetInstanceProcAddr gipa, VkInstance instance, VkDevice device, const char** missing)
{
	*missing = nullptr;
#define ARMFLASH_VK_LOAD_INSTANCE(name) \
	name = reinterpret_cast<PFN_##name>(gipa(instance, #name)); \
	if (!name && !*missing) \
		*missing = #name;
	ARMFLASH_VK_INSTANCE_FUNCTIONS(ARMFLASH_VK_LOAD_INSTANCE)
#undef ARMFLASH_VK_LOAD_INSTANCE
	if (!vkGetDeviceProcAddr)
		return false;
#define ARMFLASH_VK_LOAD_DEVICE(name) \
	name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name)); \
	if (!name && !*missing) \
		*missing = #name;
	ARMFLASH_VK_DEVICE_FUNCTIONS(ARMFLASH_VK_LOAD_DEVICE)
#undef ARMFLASH_VK_LOAD_DEVICE
	return *missing == nullptr;
}

// --- GSDeviceVK --------------------------------------------------------------------------------

namespace
{
	GSDeviceVK* s_device_instance = nullptr;
}

GSDeviceVK::GSDeviceVK(VkInstance instance, VkPhysicalDevice physical_device, VkDevice device, VkQueue queue,
	u32 queue_family, const OptionalExtensions& extensions)
	: m_instance(instance)
	, m_physical_device(physical_device)
	, m_device(device)
	, m_queue(queue)
	, m_queue_family(queue_family)
	, m_extensions(extensions)
{
	vkGetPhysicalDeviceProperties(physical_device, &m_properties);
	vkGetPhysicalDeviceMemoryProperties(physical_device, &m_memory);
}

GSDeviceVK* GSDeviceVK::GetInstance()
{
	return s_device_instance;
}

void GSDeviceVK::SetInstance(GSDeviceVK* device)
{
	s_device_instance = device;
}

s32 GSDeviceVK::FindMemoryType(u32 type_bits, VkMemoryPropertyFlags wanted) const
{
	for (u32 i = 0; i < m_memory.memoryTypeCount; i++)
	{
		if ((type_bits & (1u << i)) && (m_memory.memoryTypes[i].propertyFlags & wanted) == wanted)
			return static_cast<s32>(i);
	}
	return -1;
}

// --- VMA stand-in ------------------------------------------------------------------------------

namespace
{
	/// Allocates and binds memory for [requirements]: device-local for GPU_ONLY, else host-visible
	/// (coherent when there is such a type) and mapped for good.
	VkResult Allocate(GSDeviceVK* dev, const VkMemoryRequirements& requirements, const VmaAllocationCreateInfo& ci,
		VmaAllocation* out)
	{
		const bool host = ci.usage != VMA_MEMORY_USAGE_GPU_ONLY;
		s32 type = -1;
		bool coherent = true;
		if (host)
		{
			type = dev->FindMemoryType(requirements.memoryTypeBits,
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			if (type < 0)
			{
				type = dev->FindMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
				coherent = false;
			}
		}
		else
		{
			type = dev->FindMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | ci.requiredFlags);
			if (type < 0)
				type = dev->FindMemoryType(requirements.memoryTypeBits, 0);
		}
		if (type < 0)
			return VK_ERROR_OUT_OF_DEVICE_MEMORY;

		const VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, requirements.size, static_cast<u32>(type)};
		VkDeviceMemory memory = VK_NULL_HANDLE;
		const VkResult res = vkAllocateMemory(dev->GetDevice(), &ai, nullptr, &memory);
		if (res != VK_SUCCESS)
			return res;
		auto* allocation = new VmaAllocation_T();
		allocation->memory = memory;
		allocation->coherent = coherent;
		if (host && (ci.flags & VMA_ALLOCATION_CREATE_MAPPED_BIT))
		{
			if (vkMapMemory(dev->GetDevice(), memory, 0, VK_WHOLE_SIZE, 0, &allocation->mapped) != VK_SUCCESS)
				allocation->mapped = nullptr;
		}
		*out = allocation;
		return VK_SUCCESS;
	}

	void Free(GSDeviceVK* dev, VmaAllocation allocation)
	{
		if (!allocation)
			return;
		if (allocation->mapped)
			vkUnmapMemory(dev->GetDevice(), allocation->memory);
		vkFreeMemory(dev->GetDevice(), allocation->memory, nullptr);
		delete allocation;
	}
} // namespace

VkResult vmaCreateImage(VmaAllocator allocator, const VkImageCreateInfo* image_ci,
	const VmaAllocationCreateInfo* alloc_ci, VkImage* image, VmaAllocation* allocation, VmaAllocationInfo* info)
{
	VkResult res = vkCreateImage(allocator->GetDevice(), image_ci, nullptr, image);
	if (res != VK_SUCCESS)
		return res;
	VkMemoryRequirements requirements = {};
	vkGetImageMemoryRequirements(allocator->GetDevice(), *image, &requirements);
	res = Allocate(allocator, requirements, *alloc_ci, allocation);
	if (res == VK_SUCCESS)
		res = vkBindImageMemory(allocator->GetDevice(), *image, (*allocation)->memory, 0);
	if (res != VK_SUCCESS)
	{
		Free(allocator, *allocation);
		*allocation = nullptr;
		vkDestroyImage(allocator->GetDevice(), *image, nullptr);
		*image = VK_NULL_HANDLE;
		return res;
	}
	if (info)
		info->pMappedData = (*allocation)->mapped;
	return VK_SUCCESS;
}

void vmaDestroyImage(VmaAllocator allocator, VkImage image, VmaAllocation allocation)
{
	if (image != VK_NULL_HANDLE)
		vkDestroyImage(allocator->GetDevice(), image, nullptr);
	Free(allocator, allocation);
}

VkResult vmaCreateBuffer(VmaAllocator allocator, const VkBufferCreateInfo* buffer_ci,
	const VmaAllocationCreateInfo* alloc_ci, VkBuffer* buffer, VmaAllocation* allocation, VmaAllocationInfo* info)
{
	VkResult res = vkCreateBuffer(allocator->GetDevice(), buffer_ci, nullptr, buffer);
	if (res != VK_SUCCESS)
		return res;
	VkMemoryRequirements requirements = {};
	vkGetBufferMemoryRequirements(allocator->GetDevice(), *buffer, &requirements);
	res = Allocate(allocator, requirements, *alloc_ci, allocation);
	if (res == VK_SUCCESS)
		res = vkBindBufferMemory(allocator->GetDevice(), *buffer, (*allocation)->memory, 0);
	if (res != VK_SUCCESS)
	{
		Free(allocator, *allocation);
		*allocation = nullptr;
		vkDestroyBuffer(allocator->GetDevice(), *buffer, nullptr);
		*buffer = VK_NULL_HANDLE;
		return res;
	}
	if (info)
		info->pMappedData = (*allocation)->mapped;
	return VK_SUCCESS;
}

void vmaDestroyBuffer(VmaAllocator allocator, VkBuffer buffer, VmaAllocation allocation)
{
	if (buffer != VK_NULL_HANDLE)
		vkDestroyBuffer(allocator->GetDevice(), buffer, nullptr);
	Free(allocator, allocation);
}

VkResult vmaFlushAllocation(VmaAllocator allocator, VmaAllocation allocation, VkDeviceSize offset, VkDeviceSize size)
{
	if (!allocation || allocation->coherent)
		return VK_SUCCESS;
	const VkMappedMemoryRange range = {VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, nullptr, allocation->memory, offset, size};
	return vkFlushMappedMemoryRanges(allocator->GetDevice(), 1, &range);
}
