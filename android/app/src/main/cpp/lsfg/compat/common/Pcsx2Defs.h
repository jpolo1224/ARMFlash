// ARMFlash stand-in for PCSX2's common/Pcsx2Defs.h: only __forceinline_odr is used.
#pragma once

#define __forceinline_odr inline __attribute__((always_inline))
