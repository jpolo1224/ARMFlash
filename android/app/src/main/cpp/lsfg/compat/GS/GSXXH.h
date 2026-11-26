// ARMFlash stand-in for PCSX2's GS/GSXXH.h. The shader loader hashes the DLL only to notice it
// changed, so any stable 64-bit hash does: FNV-1a.
#pragma once

#include "common/Pcsx2Types.h"

inline u64 GSXXH3_64bits(const void* data, size_t size)
{
	const u8* p = static_cast<const u8*>(data);
	u64 h = 0xcbf29ce484222325ull;
	for (size_t i = 0; i < size; i++)
	{
		h ^= p[i];
		h *= 0x100000001b3ull;
	}
	return h;
}
