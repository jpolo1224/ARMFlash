# compat/

The frame-generation code in `../FrameGen` is ARMSX2's (`pcsx2/GS/Renderers/Vulkan/FrameGen`,
itself ported from Eden, PR #4263, and lsfg-vk; GPL-3.0-or-later). It includes PCSX2 headers by
their PCSX2 paths. The headers here stand in for those, with only what the frame-generation code
uses, so the ported files stay close to ARMSX2's and its fixes stay a readable diff.

- `Config.h`: `GSConfig` holds frame generation's settings, set by `armflash_lsfg_configure`.
- `common/Console.h`: logcat, tag `ARMFlashLSFG`.
- `common/FileSystem.h`, `common/Path.h`: plain POSIX file helpers.
- `GS/GSCacheFile.h`: a checksummed shader cache file, written to a temporary file and renamed.
- `GS/GSXXH.h`: a 64-bit FNV-1a hash (only used to notice that the DLL changed).
- `GS/Renderers/Vulkan/VKLoader.h`: Vulkan entry points loaded from the player's own
  vkGetInstanceProcAddr (a custom driver's, when one is in use), hidden inside this library.
- `GS/Renderers/Vulkan/GSDeviceVK.h`: the device frame generation runs on. In ARMFlash it is the
  native Flash player's wgpu device, handed over by fp-ruffle; it is not a GS device.
