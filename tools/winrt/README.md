# Windows plugins inside ARMFlash (work in progress)

Goal: run Flashpoint's own Windows support packs (Shockwave projector, Unity, Java, Silverlight and
the small plugins) inside ARMFlash, Play-compliant (target SDK stays current).

## What Android allows a modern app (verified on the armsx_test emulator, 2026-11-22)
- Starting a program that ships in the APK as a native library: yes.
- Mapping its own data files as code (dlopen, ld.so loading libraries): yes.
- execve() of a file the app wrote: no.
- Making a modified private file mapping executable (SELinux execmod): no. This is what broke
  Wine's PE loader ("failed to set ... protection ... noexec filesystem?").

## The chain that works
1. glibc's loader ships as `libldlinux.so` (from the base system, paths relocated).
2. Box64 0.4.4, built with Zig (`zcc`, aarch64-linux-gnu.2.36) and
   `box64-0.4.4-no-host-exec.patch` (`-DFLASHARM_NO_HOST_EXEC`: guest pages never get host
   PROT_EXEC; the dynarec only reads them), ships as `libbox64.so` with its ELF interpreter set
   (patchelf) to `/data/data/com.armsx.flasharm/r/ld`, a link the app makes to `libldlinux.so`.
   Box64 can then start, and re-start itself for every Wine process.
3. The glibc base system with Wine 10.10 (WoW64) lives in `<data>/r`. Today it is Winlator's
   rootfs (LGPL), relocated with `relocate.py` (its baked `/data/data/com.winlator/files/rootfs`
   rewritten to `/data/data/com.armsx.flasharm/r`). To be replaced by our own build.
4. Display: Termux's Android-native Xvfb (same-length relocation of `/data/data/com.termux/files/usr`
   to `/data/data/com.armsx.flasharm/x`), started through `/system/bin/linker64`. It runs xkbcomp via
   `x/bin/sh` -> `/system/bin/sh` and `x/bin/xkbcomp` -> `libxkbcomp.so` in the APK.

## Status
- Box64 runs, Wine 10.10 runs, wineserver and Windows processes start (wineboot, services.exe,
  winedevice.exe, rundll32.exe), Wine loads its X11 driver against Xvfb.
- Next: wineboot stalls with idle processes; then SPR.exe (Shockwave), then the game view
  (framebuffer to a Surface, input through XTEST), then packaging (downloadable runtime).

Debug probe: `adb shell am start -n com.armsx.flasharm/com.armsx.flashpoint.lab.LabActivity --es step <loader|box64|wine|boot|xvfb|notepad>`
(debug builds only; adb only; fixed steps).

## Findings 2026-11-22 (emulator, lab steps)

- Wine Mono and Gecko must be skipped (`WINEDLLOVERRIDES=mscoree,mshtml=`): their first-run
  download prompt (control.exe appwiz.cpl install_mono) blocks every later Wine process.
- The rootfs glibc's System V shared memory talks to a helper socket named by
  `ANDROID_SYSVSHM_SERVER`. Unset, `shmget` crashes (strncpy of NULL) inside Wine's
  XShmCreateImage path. Pointing it at a path with no listener makes shmget fail cleanly and
  Wine falls back to plain XPutImage. A real helper (ashmem fds over the socket) is a later
  speed-up. Xvfb also runs with `-extension MIT-SHM`.
- The app must stay in the foreground while Wine runs: Android's cached-app freezer stops the
  child processes otherwise.
- Notepad draws on Xvfb. Flashpoint's `FPSoftware\Shockwave\PJ101\SPR.exe` runs (WoW64, 32-bit)
  and plays a Shockwave game (Lava Lamp) loaded through ARMFlash's fp-proxy on port 22500 (the
  port in the projector's port.txt), with the game's data pack mounted.
