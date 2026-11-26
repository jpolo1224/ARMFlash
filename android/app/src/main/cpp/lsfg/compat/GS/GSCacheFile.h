// ARMFlash stand-in for PCSX2's GS/GSCacheFile.h: the shader cache as one framed file (a header
// with the kind, the stamp and a checksum, then the payload), written to a temporary file and
// renamed so a kill mid-write leaves the old cache or none.
#pragma once

#include "common/Pcsx2Types.h"

#include <string>
#include <string_view>
#include <vector>

namespace GSCacheFile
{
	inline constexpr u32 KIND_LSFG_SPIRV = 0x4C534647; // "LSFG"

	enum class ReadResult
	{
		Ok,
		Missing,
		Mismatch,
		Corrupt,
	};

	/// What a cache was made by. A file with another stamp is not read.
	class Stamp
	{
	public:
		void Add(std::string_view key, std::string_view value);
		u64 GetDigest() const;

	private:
		std::string m_text;
	};

	/// This library's identity: a cache written by another build of it is made again.
	std::string_view GetBuildId();

	bool WriteFramedFile(const std::string& path, u32 kind, const Stamp& stamp, const void* data, size_t size);
	ReadResult ReadFramedFile(const std::string& path, u32 kind, const Stamp& stamp, std::vector<u8>* out);
} // namespace GSCacheFile
