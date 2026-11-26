package com.armsx.flashpoint

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx.flashpoint.game.GameActivity
import com.armsx.flashpoint.ui.AboutPage
import com.armsx.flashpoint.ui.BrowseVm
import com.armsx.flashpoint.ui.Chip
import com.armsx.flashpoint.ui.ConfigPage
import com.armsx.flashpoint.ui.Fp
import com.armsx.flashpoint.ui.FpTheme
import com.armsx.flashpoint.ui.Shell
import kotlinx.coroutines.delay

/** The full database's size after a first download (221,660 games on 2026-11-22: 462 MB). */
/** The finished database on the device (flashpoint.sqlite after the first download, 11-2026). */
private const val DB_MB = 470
/** What the first download fetches: the game list, gzipped (89 pages of about 1.15 MB, 11-2026). */
private const val DOWNLOAD_MB = 100
/** The Windows players' runtime and 3D file together (82 + 54 MB on ARMFlash's 1.0 release). */
private const val WINDOWS_MB = 135

/**
 * The first download's progress in megabytes actually received. The total is worked out from
 * the games received so far, so it ends exactly on what came down.
 */
private fun downloaded(s: com.armsx.flashpoint.core.SyncStatus): String {
    val mb = s.bytes / 1_000_000
    if (s.phase != "Games" || s.done <= 0 || s.total <= 0) return "$mb MB downloaded"
    val about = maxOf(mb, s.bytes * s.total / s.done / 1_000_000)
    return "$mb MB of about $about MB"
}

class MainActivity : ComponentActivity() {
    companion object {
        /** Library screens alive in this process: a game from outside goes to one (LinkActivity, BootActivity). */
        @Volatile var alive = 0
            private set
        /** The game running now (its id), from launch until it hands back; null while in the library. */
        @Volatile var playing: String? = null
    }

    private val vm: BrowseVm by viewModels()

    override fun onDestroy() {
        alive--
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive++
        enableEdgeToEdge()
        UiMusic.load(this)
        com.armsx.flashpoint.ui.backdrop.LibraryBackground.load(this)
        com.armsx.flashpoint.discord.DiscordPresence.attachActivity(this)
        com.armsx.flashpoint.discord.DiscordPresence.start()
        setContent {
            FpTheme {
                Box(Modifier.fillMaxSize()) {
                    App(vm)
                    // A Discord friend coming online, over the library.
                    com.armsx.flashpoint.discord.FriendOnlineBanner(Modifier.align(androidx.compose.ui.Alignment.TopCenter))
                }
            }
        }
        vm.pendingGame = Frontend.gameId(this, intent)
        intent.getStringExtra("status")?.let { vm.status = it }
    }

    override fun onStart() {
        super.onStart()
        UiMusic.start(this)
        com.armsx.flashpoint.discord.DiscordPresence.setForeground(true)
    }

    // Pad or touch: decides whether the launcher shows its controller selection ring.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.armsx.flashpoint.ui.PadInput.onKey(event)
        // LB / RB switch the launcher's tabs.
        val shoulder = when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> -1
            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> 1
            else -> 0
        }
        if (shoulder != 0) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) com.armsx.flashpoint.ui.ShellNav.step(shoulder)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) com.armsx.flashpoint.ui.PadInput.active.value = true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) com.armsx.flashpoint.ui.PadInput.onTouch()
        return super.dispatchTouchEvent(ev)
    }

    // A frontend launch while ARMFlash is already open.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        Frontend.gameId(this, intent)?.let { vm.pendingGame = it }
        intent.getStringExtra("status")?.let { vm.status = it }
    }

    override fun onStop() {
        UiMusic.pause()
        com.armsx.flashpoint.discord.DiscordPresence.setForeground(false)
        super.onStop()
    }
}

@Composable
private fun App(vm: BrowseVm) {
    val ctx = LocalContext.current
    val pickDb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importDatabase(uri)
    }
    val playLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // Back from a game: Discord shows the library again.
        MainActivity.playing = null
        com.armsx.flashpoint.discord.DiscordPresence.setPlaying(null)
        if (r.resultCode == Activity.RESULT_OK) {
            val id = r.data?.getStringExtra(GameActivity.EXTRA_GAME_ID)
            val secs = r.data?.getLongExtra(GameActivity.EXTRA_SECONDS, 0) ?: 0
            if (id != null && secs > 0) vm.addPlaytime(id, secs)
        }
    }

    // Setup's Custom folder (GitHub build, as in ARMSX2): all-files access first, then the folder.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dir = vm.library.customLocation(uri)
        if (dir == null) vm.status = "ARMFlash cannot write to that folder. Pick another."
        else vm.pickLocation(dir)
    }
    val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) folderPicker.launch(null)
    }
    val onCustomFolder: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            runCatching {
                allFiles.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")))
            }.onFailure { runCatching { allFiles.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
        } else folderPicker.launch(null)
    }

    val play: (com.armsx.flashpoint.core.Game) -> Unit = { game ->
                    vm.prepare(game) { ready ->
                        vm.recordPlay(ready.game)
                        UiMusic.pause()
                        com.armsx.flashpoint.discord.DiscordPresence.setPlaying(
                            com.armsx.flashpoint.discord.PlayingGame(ready.game.id, ready.game.title, ready.game.primaryPlatform, vm.library.logoUrl(ready.game.id)),
                        )
                        val web = GameActivity.intent(
                            ctx, ready.game.id, ready.plan.runner, ready.plan.url, ready.zips,
                            vm.library.htdocs.path, vm.library.legacyBase, ready.game.title, vm.library.logoUrl(ready.game.id),
                        )
                        // A Flash game desktop Flashpoint runs in its browser (Flashpoint Navigator):
                        // remember that program, so Flash Player can take over from the pause menu.
                        if (vm.isFlashPage(ready.game, ready.plan)) web.putExtra(com.armsx.flashpoint.win.WinActivity.EXTRA_APP, ready.game.applicationPath)
                            .putExtra(com.armsx.flashpoint.win.WinActivity.EXTRA_PLATFORM, ready.game.primaryPlatform)
                        val native = ready.plan.runner == com.armsx.flashpoint.game.Runner.FLASH && com.armsx.flashpoint.game.FlashPrefs.player(ctx, ready.game.id) == "native"
                        MainActivity.playing = ready.game.id
                        playLauncher.launch(
                            when {
                                ready.winApp != null -> com.armsx.flashpoint.win.WinActivity.intent(ctx, web, ready.winApp, ready.winArgs, vm.library.root.path, ready.game.primaryPlatform)
                                native -> com.armsx.flashpoint.game.RuffleActivity.intent(ctx, web)
                                else -> web
                            },
                        )
                    }
                }
    // A frontend or home-screen shortcut asked for a game: play it once the library is ready.
    LaunchedEffect(vm.pendingGame, vm.dbReady) {
        val id = vm.pendingGame ?: return@LaunchedEffect
        // My Games needs no database: a .swf opened with ARMFlash plays even before setup.
        val mine = com.armsx.flashpoint.data.LocalGames.isLocal(id)
        if (!vm.dbReady && !mine) return@LaunchedEffect
        val game = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (mine) vm.library.local.game(id) else runCatching { com.armsx.flashpoint.core.FpCore.game(id) }.getOrNull()
        }
        // A game this launch closed (a frontend asking for another) ends its own process on the
        // way out, and a game started before that lands in the dying process and goes with it.
        // Games always end their process, so one still there is on its way out: wait for it.
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val proc = ctx.packageName + ":game"
        var waited = 0
        while (waited++ < 50 && am.runningAppProcesses?.any { it.processName == proc } == true) delay(100)
        // Cleared only now: pendingGame keys this effect, so clearing it before the lookup
        // restarted the effect and cancelled the lookup, and the game never started.
        vm.pendingGame = null
        if (game != null) play(game) else vm.status = if (mine) "That game is no longer in My Games." else "That game is not in the database."
    }

    // Check on Launch (Config, Updates): one quiet check at start, GitHub build only.
    if (com.armsx.flashpoint.BuildConfig.IN_APP_UPDATER) com.armsx.flashpoint.update.AutoUpdateGate()

    // The library's background (ui/backdrop), edge to edge under the system bars too.
    if (com.armsx.flashpoint.ui.backdrop.LibraryBackground.active) com.armsx.flashpoint.ui.backdrop.LibraryBackdrop()
    Box(Modifier.fillMaxSize().background(Fp.Base).safeDrawingPadding()) {
        if (!vm.dbReady) {
            if (vm.opening) Opening() else Setup(vm, onImport = { pickDb.launch(arrayOf("*/*")) }, onCustomFolder = onCustomFolder)
        } else {
            BackHandler(enabled = vm.selected != null) { vm.clearSelection() }
            Shell(
                vm,
                onPlay = play,
                configPage = { ConfigPage(vm, onImport = { pickDb.launch(arrayOf("*/*")) }) },
                aboutPage = { AboutPage() },
            )
        }
        vm.bigDownload?.let { big ->
            val fits = big.free > big.bytes + (64L shl 20)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.bigDownload = null },
                title = { Text("Big download") },
                text = {
                    Text(
                        "${big.title} needs ${com.armsx.flashpoint.ui.sizeText(big.bytes)} of game data " +
                            "(${com.armsx.flashpoint.ui.sizeText(big.free)} free). It downloads once, then plays offline. " +
                            if (fits) "A download that gets cut off picks up where it stopped." else "There is not enough free space for it.",
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = big.go, enabled = fits) { Text("Download") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.bigDownload = null }) { Text("Cancel") }
                },
            )
        }
        vm.winAsk?.let { ask -> WindowsPlayersAsk(vm, ask) }
        if (vm.status.isNotBlank()) {
            LaunchedEffect(vm.status) { delay(4000); vm.status = "" }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color(0xEE111111)).padding(12.dp),
            ) { Text(vm.status, color = Color.White) }
        }
    }
}

/** Start-up while the game database opens (the first-run screen used to flash by meanwhile). */
@Composable
private fun Opening() {
    Column(
        Modifier.fillMaxSize().background(Fp.Browse).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.fp_logo), contentDescription = null, modifier = Modifier.height(120.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), color = Color.White, fontWeight = FontWeight.Black, fontSize = 34.sp)
        Spacer(Modifier.height(12.dp))
        Text("Opening the game database…", color = Fp.TextDim, fontSize = 14.sp)
    }
}

/** First run: pick where the Flashpoint folder goes, then build the database or import one. */
@Composable
private fun Setup(vm: BrowseVm, onImport: () -> Unit, onCustomFolder: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadLocations() }
    val ctx = LocalContext.current
    val win = com.armsx.flashpoint.win.WinSetup
    Column(
        Modifier.fillMaxSize().background(Fp.Browse).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.fp_logo), contentDescription = null, modifier = Modifier.height(120.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), color = Color.White, fontWeight = FontWeight.Black, fontSize = 34.sp)
        Spacer(Modifier.height(12.dp))
        val s = vm.sync
        val imp = vm.importing
        when {
            s?.running == true -> {
                Text("Downloading the game database: ${s.phase}", color = Fp.Text)
                if (s.total > 0) {
                    Text("${s.done} of ${s.total} games", color = Fp.TextDim, fontSize = 13.sp)
                    Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(vertical = 10.dp).height(8.dp).background(Fp.Field)) {
                        Box(Modifier.fillMaxWidth((s.done.toFloat() / s.total).coerceIn(0f, 1f)).height(8.dp).background(Fp.Play))
                    }
                }
                if (s.bytes > 0) Text(downloaded(s), color = Fp.TextDim, fontSize = 13.sp)
                if (vm.syncEta.isNotEmpty()) {
                    Text(vm.syncEta, color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                }
                Chip("Cancel", on = false) { vm.cancelSync() }
                WindowsProgress(win)
            }
            imp != null -> {
                Text("Importing: ${imp / (1 shl 20)} MB", color = Fp.Text)
                WindowsProgress(win)
            }
            else -> {
                Text(
                    "ARMFlash needs Flashpoint's game database: the details of over 170,000 games. " +
                        "Download it from Flashpoint Archive, or import flashpoint.sqlite from a desktop install. " +
                        "Games download on demand the first time you play them.",
                    color = Fp.Text, modifier = Modifier.widthIn(max = 560.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Download: about $DOWNLOAD_MB MB, usually 5 to 10 minutes. The database it builds takes about $DB_MB MB, " +
                        "and building it needs about 1 GB free, plus room for the games you play.",
                    color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.widthIn(max = 560.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "The Windows players download with it, for Shockwave, Unity, Java and the other games that run in a " +
                        "Windows player: about $WINDOWS_MB MB more, about 1 GB once set up.",
                    color = Fp.Text, fontSize = 14.sp, modifier = Modifier.widthIn(max = 560.dp),
                )
                WindowsProgress(win)
                Spacer(Modifier.height(18.dp))
                Text("Where to keep the database and games", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                vm.locations.forEach { loc ->
                    val on = loc.dir.path == vm.location.path
                    Chip(
                        "${if (on) "\u2713 " else ""}${if (loc.removable) "SD card: " else ""}${loc.label}  (${"%.1f".format(loc.freeBytes / 1e9)} GB free)",
                        on = on,
                    ) { vm.pickLocation(loc.dir) }
                    Spacer(Modifier.height(6.dp))
                }
                if (BuildConfig.STORAGE_ALL_FILES && android.os.Build.VERSION.SDK_INT >= 30) {
                    val custom = vm.locations.none { it.dir.path == vm.location.path }
                    Chip(if (custom) "\u2713 Custom folder: ${vm.location.path}" else "Custom folder\u2026", on = custom, onClick = onCustomFolder)
                    Spacer(Modifier.height(6.dp))
                }
                if (vm.locations.size > 1) Text(
                    "Removing the SD card later hides your games until it is back.",
                    color = Fp.TextDim, fontSize = 12.sp, modifier = Modifier.widthIn(max = 560.dp),
                )
                com.armsx.flashpoint.ui.SetupDriverOffer()
                Spacer(Modifier.height(20.dp))
                Chip("Download the game database", on = true) { vm.syncDatabase(fresh = true); win.start(ctx) }
                Spacer(Modifier.height(10.dp))
                Chip("Import flashpoint.sqlite", on = false) { onImport(); win.start(ctx) }
            }
        }
    }
}

/** The Windows players' download as setup runs (it keeps going after setup, shown in Config). */
@Composable
private fun WindowsProgress(win: com.armsx.flashpoint.win.WinSetup) {
    val line = win.progress ?: win.failed ?: return
    Spacer(Modifier.height(8.dp))
    Text(line, color = Fp.TextDim, fontSize = 13.sp, modifier = Modifier.widthIn(max = 560.dp))
}

/**
 * A Windows game with the Windows players or their 3D file not downloaded yet: what they are, a
 * Download that starts the game once they are in, and the download's progress meanwhile.
 */
@Composable
private fun WindowsPlayersAsk(vm: BrowseVm, ask: BrowseVm.WinAsk) {
    val win = com.armsx.flashpoint.win.WinSetup
    val only3d = ask.parts == listOf(com.armsx.flashpoint.win.WinSetup.Part.GRAPHICS)
    val busy = win.progress != null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.winAsk = null },
        title = { Text(if (only3d) "3D for Windows players" else "Windows players needed") },
        text = {
            Column {
                Text(
                    if (only3d) "${ask.game.title} runs in a Windows player. Its 3D file (about 54 MB) lets Windows games " +
                        "draw 3D (Unity, Shockwave 3D). It downloads once, for every Windows game."
                    else "${ask.game.title} runs in a Windows player. The Windows players and their 3D file " +
                        "(about 135 MB) download once, then every Windows game uses them.",
                )
                (win.progress ?: win.failed)?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            if (!busy) androidx.compose.material3.TextButton(onClick = { vm.winAskDownload() }) {
                Text(if (win.failed != null) "Try again" else "Download")
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (only3d && !busy) androidx.compose.material3.TextButton(onClick = { vm.winAskWithout3d() }) { Text("Play without 3D") }
                androidx.compose.material3.TextButton(onClick = { vm.winAsk = null }) { Text(if (busy) "Hide" else "Cancel") }
            }
        },
    )
}
