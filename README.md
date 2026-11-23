<p align="center"><img src="docs/armflash-logo.png" width="160" alt="ARMFlash logo"></p>

# ARMFlash: Flashpoint Archive for Android (native ARM64)

A standalone Android port of Flashpoint Archive. No containers, no Wine-in-an-app,
no Termux: everything is compiled into the APK for arm64-v8a.

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

## Phases

| Phase | What | State |
|---|---|---|
| 1 | Game server, database, downloads, launcher UI, WebView runner | **Done**, tested on the AVD (HTML5 + Flash from zips) |
| 2 | Native Ruffle (wgpu/Vulkan) reading zips in-process via `GameServer::read` | Next |
| 3 | Touch overlay: virtual mouse/trackpad, keys, D-pad, per-game layouts; controllers | |
| 2b | AS3 (AVM2) to ARM64 JIT inside Ruffle, after profiling real games | |
| 4 | Shockwave (ScummVM Director), Java (applets) | |
| 5 | Unity Web Player / Silverlight: ARM64 Wine + wowbox64 + NPAPI host (Pipelight-style) | |

## Build

```
scripts/fetch-ruffle-web.sh           # once
cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Host tests: `cd rust && cargo test`.
Test library for a device: `cargo run -p fp-jni --example make_test_library -- <dir>/Flashpoint`,
then copy the folder to `Android/data/com.armsx.flasharm/files/`.

## Open items

- The default game data source URL is unverified (the launcher ships none); set it in Config.
- HTTPS requests from games are refused (CONNECT -> 403) until TLS interception lands.
- PHP-backed Legacy games need a PHP runtime (Flashpoint-Router); not yet.
- Playlists from `Data/Playlists/*.json` are not imported yet; Favorites are local.
