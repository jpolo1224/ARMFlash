package com.armsx.flashpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import com.armsx.flashpoint.UiMusic
import com.armsx.flashpoint.core.FpCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The launcher's Config tab: its sections and options, where they apply on Android. */
@Composable
fun ConfigPage(vm: BrowseVm, onImport: () -> Unit) {
    val ctx = LocalContext.current
    val p = vm.prefs2
    val lib = vm.library
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    Column(Modifier.fillMaxSize().background(Fp.Browse).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Config", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)

        Section("Preferences") {
            Toggle("Show Extreme Filters", "Allows Extreme tag filters to be toggled, to control content unsuitable for children.", p.extremeGames) { vm.refreshAll() }
            Toggle("Hide Extreme Screenshots", "Hides screenshots of Extreme tagged content.", p.hideExtremeScreenshots)
            ConfigRow("Default Opening Page", "Default page to open to.") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Home", p.defaultOpeningPage.value == "home") { p.defaultOpeningPage.value = "home" }
                    Chip("Games", p.defaultOpeningPage.value == "games") { p.defaultOpeningPage.value = "games" }
                }
            }
        }

        Section("On Demand Images") {
            Toggle("Enabled", "Download missing logos and screenshots as they are needed.", p.onDemandImages)
            Toggle("Compressed Images", "Downloads compressed images instead, saving about 80% of the space.", p.onDemandImagesCompressed)
            Action("Delete All Images", "Deletes all downloaded logos and screenshots.", "Delete") {
                confirm = "Delete all downloaded logos and screenshots?" to {
                    ctx.imageLoader.diskCache?.clear()
                    ctx.imageLoader.memoryCache?.clear()
                }
            }
        }

        Section("Playtime Tracking") {
            Toggle("Track Playtime", "Track last played date, total playtime and play count for games.", p.enablePlaytimeTracking)
            Toggle("Track Extreme Games Playtime", "Track playtime for Extreme games too.", p.enablePlaytimeTrackingExtreme)
            Action("Clear Playtime Data", "Clears last played, total playtime and play count for every game. Irreversible.", "Clear Data") {
                confirm = "Clear playtime data for every game? This cannot be undone." to {
                    scope.launch { withContext(Dispatchers.IO) { runCatching { FpCore.clearPlaytimeData() } } }
                }
            }
        }

        Section("Flashpoint") {
            ConfigRow("Flashpoint Path", lib.root.path) {}
            ConfigRow("Random Libraries", "Libraries to use for Random Picks on the home page.") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("arcade" to "Games", "theatre" to "Animations").forEach { (id, label) ->
                        val on = id in p.randomLibraries.value
                        Chip(label, on) {
                            p.randomLibraries.value = if (on) p.randomLibraries.value - id else p.randomLibraries.value + id
                        }
                    }
                }
            }
            TextSetting("Metadata Server Host", "Used for syncing new and updated game info.", lib.metadataBase) { lib.metadataBase = it }
            TextSetting("Game Data Source", "Where game data packs download from.", lib.gameDataSource) { lib.gameDataSource = it }
            TextSetting("Images Server", "Where logos and screenshots download from.", lib.imagesBase) { lib.imagesBase = it }
            var legacy by remember { mutableStateOf(lib.legacyOnline) }
            ConfigRow("Legacy Files Online", "Fetch Legacy game files from Flashpoint's server when they are not on the device.") {
                Switch(legacy, { legacy = it; lib.legacyOnline = it })
            }
            Action("Update Game Info", "Last updated: ${vm.lastSync.take(10).ifBlank { "never" }}", "Update") { vm.syncDatabase() }
            Action("Import Database", "Replace the game database with a flashpoint.sqlite from a desktop install.", "Import", onImport)
        }

        Section("Flash") {
            ConfigRow("Flash Player", "Native runs Ruffle directly on the GPU (fastest). Browser runs Ruffle's web version, for the rare game that needs it.") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Native", p.flashPlayer.value == "native") { p.flashPlayer.value = "native" }
                    Chip("Browser", p.flashPlayer.value == "web") { p.flashPlayer.value = "web" }
                }
            }
            ConfigRow("Graphics", "What native Flash draws with. Auto uses Vulkan where it works and OpenGL ES otherwise.") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "Auto", "vulkan" to "Vulkan", "gl" to "OpenGL ES").forEach { (v, label) ->
                        Chip(label, p.flashBackend.value == v) { p.flashBackend.value = v }
                    }
                }
            }
        }

        Section("Visuals") {
            ConfigRow("Screenshot Preview", "Show a game's screenshot in the grid.") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("0" to "Off", "1" to "Long Press", "2" to "Always").forEach { (v, label) ->
                        Chip(label, p.screenshotPreviewMode.value == v) { p.screenshotPreviewMode.value = v }
                    }
                }
            }
            var music by remember { mutableStateOf(UiMusic.enabled.value) }
            var vol by remember { mutableStateOf(UiMusic.volume.floatValue) }
            ConfigRow("Launcher Music", "Background music while browsing.") {
                Switch(music, {
                    music = it; UiMusic.enabled.value = it; UiMusic.save(ctx)
                    if (it) UiMusic.start(ctx) else UiMusic.stop()
                })
            }
            Slider(
                value = vol, onValueChange = { vol = it; UiMusic.volume.floatValue = it; UiMusic.applyVolume() },
                onValueChangeFinished = { UiMusic.save(ctx) }, enabled = music, modifier = Modifier.widthIn(max = 420.dp),
            )
        }

        Section("Advanced") {
            Action("Optimize Database", "Run maintenance tasks to increase database performance and reduce size.", "Optimize") {
                scope.launch {
                    vm.status = "Optimizing database"
                    withContext(Dispatchers.IO) { runCatching { FpCore.optimizeDatabase() } }
                    vm.status = "Database optimized"
                }
            }
            Toggle("Verbose Logging", "Enables verbose logging for the game server and database.", p.enableVerboseLogging)
        }
        Spacer(Modifier.height(30.dp))
    }

    confirm?.let { (msg, run) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { run(); confirm = null }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(14.dp))
    HomeBox(title) { content() }
}

@Composable
private fun Toggle(title: String, desc: String, pref: AppPrefs.Pref<Boolean>, after: () -> Unit = {}) {
    ConfigRow(title, desc) { Switch(pref.state.value, { pref.value = it; after() }) }
}

@Composable
private fun Action(title: String, desc: String, button: String, onClick: () -> Unit) {
    ConfigRow(title, desc) { Chip(button, on = false, onClick = onClick) }
}

@Composable
private fun TextSetting(title: String, desc: String, initial: String, save: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(desc, color = Fp.TextDim, fontSize = 13.sp)
        OutlinedTextField(v, { v = it; save(it) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}
