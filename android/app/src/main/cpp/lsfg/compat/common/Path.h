// ARMFlash stand-in for PCSX2's common/Path.h.
#pragma once

#include <string>
#include <string_view>

namespace Path
{
	std::string Combine(std::string_view base, std::string_view next);
} // namespace Path
