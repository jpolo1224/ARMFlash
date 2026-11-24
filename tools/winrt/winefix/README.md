# Wine fixes

Fixes from later Wine versions, applied to the runtime's Wine 10.10 files. The APK carries the
fixed files (`android/app/src/main/assets/winrt/`) and `WinRuntime.ensureWineFixes` puts each
over the runtime's own copy at the start of a Windows game, only when that copy is exactly the
file the fix was made from.

## iphlpapi.dll (32-bit)

Wine commit 4781a712, "iphlpapi: Avoid out-of-bounds read if no network interfaces are present"
(https://gitlab.winehq.org/wine/wine/-/commit/4781a712): `GetAdaptersAddresses` returns
`ERROR_NO_DATA` when there are no interfaces. Android hides network interfaces from apps, so Wine
finds none, and Wine 10.10 then read past an empty adapter list and crashed the program
(Merlin's Revenge's Shockwave projector, as the game loaded).

The function's code is compiled into `GetAdaptersAddresses`; the patch sends the instruction
after the adapter count is read to a few bytes at the end of `.text` that make the same check
as the upstream source change, then carry on as before.

    python3 iphlpapi_i386.py <runtime>/opt/wine/lib/wine/i386-windows/iphlpapi.dll iphlpapi-i386.dll

Wine is LGPL-2.1; its source is at https://gitlab.winehq.org/wine/wine (tag wine-10.10 plus the
commit above).
