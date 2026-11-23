# Third-party notices

ARMFlash is built on the work of many people. The app's About page lists the same credits,
along with Flashpoint's full list of contributors (Flashpoint's `credits.json`).

## Flashpoint Archive

- **flashpoint-archive (FPA-Rust)**: Flashpoint's database library. ARMFlash uses it as a Rust dependency.
  MIT License, FlashpointProject. https://github.com/FlashpointProject/FPA-Rust
- **Flashpoint Launcher data**: the themes, logo sets and credits list bundled in the app
  (`android/app/src/main/assets/flashpoint/Data`). They come from the Flashpoint Launcher, by Flashpoint's
  contributors and the theme authors named in each theme (MIT License).
  https://github.com/FlashpointProject/launcher
- **App icon**: the Flashpoint logo by the Flashpoint Archive creators, in a version by u/Alarming-Brick-3670.
- **Flashpoint's game database, game data and support packs** are downloaded from Flashpoint's servers
  when needed. They are not part of this repository.

## Players

- **Ruffle** (Flash): MIT or Apache-2.0. https://github.com/ruffle-rs/ruffle
- **Box64** (runs the Windows players' x86 code): MIT License, ptitSeb and contributors.
  https://github.com/ptitSeb/box64. ARMFlash builds version 0.4.4 with the changes in
  `tools/winrt/box64-0.4.4-armflash.patch`.
- **Secure Player**: ARMFlash's own stand-in, following Flashpoint Secure Player by tomysshadow (MIT).
  https://github.com/FlashpointProject/FlashpointSecurePlayer

## Windows support download (`ARMFlash-windows-1.tar.zst`)

This is the file that Config, under Windows Players, downloads from this repository's v0.1.0 release.
It holds:

- **Wine 10.10** (LGPL-2.1-or-later), as built for Winlator. Source: https://gitlab.winehq.org/wine/wine
  (tag wine-10.10) and https://github.com/brunodev85/winlator
- **Winlator's base system**: GNU libc (LGPL-2.1-or-later) and the libraries Wine needs, by brunodev85 and the
  respective projects. Source: https://github.com/brunodev85/winlator. ARMFlash relocates its paths
  (`tools/winrt/relocate.py`) and changes nothing else.
- **Xvfb** from X.Org (MIT License), as built by Termux, with the libraries it loads (X.Org's libraries under
  MIT, the others under their own licenses) and its keyboard data. Source:
  https://gitlab.freedesktop.org/xorg/xserver and https://github.com/termux/termux-packages

If you got this file from ARMFlash and want the source of any LGPL part, open an issue in this repository.

## 3D download (`ARMFlash-3d-1.tar.zst`)

Mesa with llvmpipe (MIT License), LLVM (Apache-2.0 with LLVM exceptions), libglvnd (MIT License) and ICU
(Unicode License), taken from Ubuntu's packages (`tools/winrt/pack3d.py`). Source: https://mesa3d.org,
https://llvm.org, https://gitlab.freedesktop.org/glvnd/libglvnd, https://icu.unicode.org and Ubuntu's source packages.

## Downloaded on demand, never shipped

- **Wine Gecko 2.47.4** (MPL-2.0), downloaded from WineHQ when a game needs Internet Explorer.
- **MFC 4.2** from Microsoft's Visual C++ 6 runtime, downloaded from Microsoft when a game needs it.

## PHP

PHP 7.4 (PHP License 3.01) from Debian, with libxml2 and libxslt (MIT), ICU (Unicode License), OpenSSL
(OpenSSL and SSLeay licenses), Oniguruma (BSD), PCRE2 (BSD), SQLite (public domain), libsodium (ISC),
Argon2 (CC0), zlib, bzip2, XZ (public domain), libmagic (BSD), libgcrypt and libgpg-error (LGPL-2.1),
ncurses (MIT), libedit (BSD), libffi (MIT) and GNU libc (LGPL-2.1). Source: Debian's source packages.

## App libraries

- **libadrenotools** (custom GPU drivers): BSD 2-Clause, Billy Laws. https://github.com/bylaws/libadrenotools
- **AndroidX, Jetpack Compose, Kotlin and kotlinx libraries**: Apache-2.0.
- **Rust crates** (tokio, hyper, rustls, ring, zip, rusqlite with SQLite and others): MIT, Apache-2.0,
  ISC or similar permissive licenses, as listed in each crate.

## Sounds, music and cursors

- **UI music**: "Awestruck" by isaiah658.
- **Pause menu music**: "Underwater Ambient Pad" by isaiah658.
- **Menu sounds**: Interface SFX Pack 1 by obsydianx (CC0), trimmed by the ARMSX2 team.
- **Cursors**: Capitaine Cursors by Keefer Rourke and others (LGPL-3.0); DMZ-White by Novell (CC BY-SA 3.0).

## From ARMSX2

Touch control sprites, the in-game menu, the battery indicator, the custom driver manager, Discord
presence, home screen shortcuts and the controller focus ring come from ARMSX2, by the ARMSX2 team
(GPL-3.0, the license ARMFlash itself uses).
