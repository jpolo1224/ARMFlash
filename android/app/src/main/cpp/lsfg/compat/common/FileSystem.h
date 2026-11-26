// ARMFlash stand-in for PCSX2's common/FileSystem.h: the few calls the shader loader makes.
#pragma once

#include "common/Pcsx2Types.h"

#include <optional>
#include <vector>

struct FILESYSTEM_STAT_DATA
{
	s64 Size = 0;
	s64 ModificationTime = 0;
};

namespace FileSystem
{
	bool StatFile(const char* path, FILESYSTEM_STAT_DATA* sd);
	bool FileExists(const char* path);
	bool DeleteFilePath(const char* path);
	std::optional<std::vector<u8>> ReadBinaryFile(const char* path);
} // namespace FileSystem
