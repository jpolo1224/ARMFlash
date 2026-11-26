// ARMFlash stand-in for PCSX2's common/Console.h: printf-style lines to logcat (tag ARMFlashLSFG).
#pragma once

struct ArmflashConsole
{
	void WriteLn(const char* format, ...) const __attribute__((format(printf, 2, 3)));
	void Warning(const char* format, ...) const __attribute__((format(printf, 2, 3)));
	void Error(const char* format, ...) const __attribute__((format(printf, 2, 3)));
};

extern const ArmflashConsole Console;
