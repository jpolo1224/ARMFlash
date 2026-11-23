<p align="center"><img src="docs/armflash-logo.png" width="160" alt="ARMFlash logo"></p>

# ARMFlash: Flashpoint Archive for Android (native ARM64)

A standalone Android port of Flashpoint Archive for arm64-v8a. Flash and HTML5 games play
in the app itself; Shockwave, Java and PopCap games run Flashpoint's own Windows players
under Wine and Box64, from a one-time download in Config.

## What it plays

| Platform | How |
|---|---|
| Flash | Ruffle, built into the app: natively on the GPU, or its web version in the browser. The pause menu can switch a game to Flashpoint's own Flash Player. |
| HTML5 | Android's WebView, through the app's game server, so pages load from their original addresses |
| Shockwave, Java, PopCap Plugin | Flashpoint's Windows players, run by Wine and Box64 (Config, Windows Players, Download) |

Games that need PHP on the server side run with the bundled PHP 7.4. Touch controls and game
controllers work across all of them.

## Requirements

| | Minimum |
|---|---|
| Android | 9 or newer |
| Processor | 64-bit ARM (arm64-v8a) |
| Graphics | Vulkan or OpenGL ES 3.0, for native Flash |
| Memory | 3 GB of RAM; 4 GB or more recommended for Windows players |
| Storage | About 100 MB for the app and 470 MB for the game database, plus the games you play |
| Windows players | About 1 GB more: Windows support (about 500 MB), its Windows setup on first run (about 350 MB) and each game's support pack; 3D games add about 225 MB |
| Internet | To download the game database (or import it from a desktop install), games and Windows support |

Custom GPU drivers are for Snapdragon (Adreno) devices. Windows players emulate an x86 PC, so
they run best on a recent high-end chip.

## Layout

```
rust/
  fp-proxy/   Flashpoint-compatible game server (library). Streams files straight out of
              data-pack zips: content/<host>/<path>, case-insensitive, mount order,
              index files, dir redirects, htdocs (with path?query variants), remote Legacy
              fallback with caching. Same lookup rules as FlashpointGameServer + zipfs.
  fp-jni/     libfpcore.so: JNI bridge. Database = flashpoint-archive (FPA-Rust, MIT), the
              library the desktop launcher itself runs on, so search syntax, filters,
              sorting and keyset paging match the launcher exactly.
android/      Kotlin + Jetpack Compose app (AGP 9, built-in Kotlin).
scripts/
  build-native.sh      cargo + NDK clang -> android/app/src/main/jniLibs (Gradle runs it)
  fetch-ruffle-web.sh  Ruffle web build into assets (interim Flash player)
```

On-device data uses the desktop folder layout, so files copy across as-is:
`Flashpoint/Data/flashpoint.sqlite`, `Flashpoint/Data/Games/<id>-<ms>.zip`, `Flashpoint/Legacy/htdocs/`.

## How a game runs

1. The launcher downloads the active data pack (`<source><gameId>-<dateAdded ms>.zip`),
   checks SHA-256, records it in the database (same steps as the desktop launcher).
2. `GameActivity` starts in its own `:game` process: its own game server on 127.0.0.1,
   the zip mounted (no extraction), and a WebView routed through it with
   androidx.webkit `ProxyController`. Pages load from their original URLs.
3. Flash: Ruffle's player page is served under `/__fpm/` on the game's own host (so
   sitelocks see their domain); Ruffle is also injected at document start so pages that
   embed Flash work. HTML5 runs in the WebView (V8 JIT).
4. Leaving the game kills the `:game` process; playtime is written back.

## Build

```
scripts/fetch-ruffle-web.sh           # once
cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Host tests: `cd rust && cargo test`.
Test library for a device: `cargo run -p fp-jni --example make_test_library -- <dir>/Flashpoint`,
then copy the folder to `Android/data/com.armsx.flasharm/files/`.

## License

ARMFlash is free software under the GNU General Public License, version 3 ([LICENSE](LICENSE)).
It includes code from ARMSX2 (GPL-3.0) and builds on many other projects, listed with their
licenses and sources in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Flashpoint Archive,
its game database and its games belong to their respective owners.

<img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/8e6ed929-9c57-49cb-9e1d-fda576658e15" />
