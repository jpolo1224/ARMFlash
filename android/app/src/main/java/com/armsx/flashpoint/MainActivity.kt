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
private const val DB_MB = 470

class MainActivity : ComponentActivity() {
    private val vm: BrowseVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UiMusic.load(this)
        com.armsx.flashpoint.discord.DiscordPresence.attachActivity(this)
        com.armsx.flashpoint.discord.DiscordPresence.start()
        setContent { FpTheme { App(vm) } }
        vm.pendingGame = Frontend.gameId(this, intent)
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
                        val native = ready.plan.runner == com.armsx.flashpoint.game.Runner.FLASH && com.armsx.flashpoint.game.FlashPrefs.player(ctx, ready.game.id) == "native"
                        playLauncher.launch(
                            when {
                                ready.winApp != null -> com.armsx.flashpoint.win.WinActivity.intent(ctx, web, ready.winApp, ready.winArgs, vm.library.root.path)
                                native -> com.armsx.flashpoint.game.RuffleActivity.intent(ctx, web)
                                else -> web
                            },
                        )
                    }
                }
    // A frontend or home-screen shortcut asked for a game: play it once the library is ready.
    LaunchedEffect(vm.pendingGame, vm.dbReady) {
        val id = vm.pendingGame ?: return@LaunchedEffect
        if (!vm.dbReady) return@LaunchedEffect
        vm.pendingGame = null
        val game = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.armsx.flashpoint.core.FpCore.game(id) }.getOrNull() }
        if (game != null) play(game) else vm.status = "That game is not in the database."
    }

    Box(Modifier.fillMaxSize().background(Fp.Header).safeDrawingPadding()) {
        if (!vm.dbReady) {
            Setup(vm, onImport = { pickDb.launch(arrayOf("*/*")) }, onCustomFolder = onCustomFolder)
        } else {
            BackHandler(enabled = vm.selected != null) { vm.clearSelection() }
            Shell(
                vm,
                onPlay = play,
                configPage = { ConfigPage(vm, onImport = { pickDb.launch(arrayOf("*/*")) }) },
                aboutPage = { AboutPage() },
            )
        }
        if (vm.status.isNotBlank()) {
            LaunchedEffect(vm.status) { delay(4000); vm.status = "" }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color(0xEE111111)).padding(12.dp),
            ) { Text(vm.status, color = Color.White) }
        }
    }
}

/** First run: pick where the Flashpoint folder goes, then build the database or import one. */
@Composable
private fun Setup(vm: BrowseVm, onImport: () -> Unit, onCustomFolder: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadLocations() }
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
                if (vm.syncBytes > 0) Text(
                    "${vm.syncBytes / (1 shl 20)} MB of about $DB_MB MB", color = Fp.TextDim, fontSize = 13.sp,
                )
                if (vm.syncEta.isNotEmpty()) {
                    Text(vm.syncEta, color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                }
                Chip("Cancel", on = false) { vm.cancelSync() }
            }
            imp != null -> Text("Importing: ${imp / (1 shl 20)} MB", color = Fp.Text)
            else -> {
                Text(
                    "ARMFlash needs Flashpoint's game database: the details of over 220,000 games and animations. " +
                        "Download it from Flashpoint Archive, or import flashpoint.sqlite from a desktop install. " +
                        "Games download on demand the first time you play them.",
                    color = Fp.Text, modifier = Modifier.widthIn(max = 560.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Download: about $DB_MB MB, usually 5 to 10 minutes. It needs about $DB_MB MB of space, plus room for the games you play.",
                    color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.widthIn(max = 560.dp),
                )
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
                Chip("Download the game database", on = true) { vm.syncDatabase(fresh = true) }
                Spacer(Modifier.height(10.dp))
                Chip("Import flashpoint.sqlite", on = false, onClick = onImport)
            }
        }
    }
}
