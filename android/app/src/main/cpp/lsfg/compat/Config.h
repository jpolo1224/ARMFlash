// ARMFlash stand-in for PCSX2's Config.h. The ported code reads its settings as GSConfig.Lsfg*;
// here they are frame generation's own, set by armflash_lsfg_configure() on the player's render
// thread, which is also the only thread that runs frame generation.
#pragma once

#include "common/Pcsx2Types.h"

#include <string>

struct ArmflashLsfgConfig
{
	bool LsfgEnabled = false;
	/// Frames shown per rendered frame: 2, 3 or 4.
	u32 LsfgMultiplier = 2;
	/// Motion detail: 25..99 a fixed share of the screen, 100 automatic.
	u8 LsfgFlowScale = 100;
	/// The half-precision shaders, when the device was created with shaderFloat16.
	bool LsfgFp16 = false;
	/// Kept for the ported code; ARMSX2's passes always use the performance shaders.
	bool LsfgPerformance = true;
	/// Adaptive pacing's target in Hz (the display's rate), 0 for a fixed multiplier.
	u32 LsfgTargetRate = 0;
	/// The user's own Lossless.dll, copied into the app's files.
	std::string LsfgDllPath;
	/// Where the translated shader cache is kept.
	std::string CacheDir;
};

extern ArmflashLsfgConfig GSConfig;
