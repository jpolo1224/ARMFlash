package com.armsx.flashpoint.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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

    Column(Modifier.fillMaxSize().background(Fp.Browse).padVerticalScroll().padding(16.dp)) {
        Text("Config", color = Fp.Strong, fontWeight = FontWeight.Black, fontSize = 24.sp)

        Section("Theme") {
            ConfigRow("Theme", "Colours for the launcher.") {
                ThemeMenu()
            }
            ConfigRow("Logo Set", "Platform logos. Copy a desktop install's Data/LogoSets folders into Flashpoint/Data/LogoSets here to add sets.") {
                LogoSetMenu()
            }
            LogoSets.current.value?.let { set ->
                LogoPreview(set)
            }
        }

        Section("Graphics Driver") {
            DriverSection(vm)
        }


        Section("Renderer") {
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
            var live by remember { mutableStateOf(lib.liveWeb) }
            ConfigRow("Live Web Fallback", "When a file is in neither the game's data nor Legacy, ask the site it originally came from. Some games need this to start.") {
                Switch(live, { live = it; lib.liveWeb = it })
            }
            Action("Update Game Info", "Last updated: ${vm.lastSync.take(10).ifBlank { "never" }}", "Update") { vm.syncDatabase() }
            Action("Import Database", "Replace the game database with a flashpoint.sqlite from a desktop install.", "Import", onImport)
        }

        Section("Visuals") {
            ConfigRow("Screenshot Preview", "Show a game's screenshot in the grid.") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("0" to "Off", "1" to "Long Press", "2" to "Always").forEach { (v, label) ->
                        Chip(label, p.screenshotPreviewMode.value == v) { p.screenshotPreviewMode.value = v }
                    }
                }
            }
            Toggle("Boot Animation", "Play the FlashARM intro with its sound when the app starts. Tap it to skip.", p.bootAnimation)
            CustomMediaRow(com.armsx.flashpoint.data.CustomMedia.Kind.BOOT_INTRO, arrayOf("video/*"), "A video of your own in place of the FlashARM intro.", onPreview = {
                ctx.startActivity(android.content.Intent(ctx, com.armsx.flashpoint.BootActivity::class.java).putExtra(com.armsx.flashpoint.BootActivity.EXTRA_PREVIEW, true))
            })
            CustomMediaRow(com.armsx.flashpoint.data.CustomMedia.Kind.UI_MUSIC, arrayOf("audio/*"), "A track of your own for the launcher.", onChanged = { UiMusic.reload(ctx) })
            CustomMediaRow(com.armsx.flashpoint.data.CustomMedia.Kind.PAUSE_MUSIC, arrayOf("audio/*"), "A track of your own for the in-game pause menu.")
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

        Section("Discord") {
            DiscordSection()
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

/** Choose, preview and reset a custom intro or track (ARMSX2's custom intro and music rows). */
@Composable
private fun CustomMediaRow(
    kind: com.armsx.flashpoint.data.CustomMedia.Kind,
    mime: Array<String>,
    desc: String,
    onChanged: () -> Unit = {},
    onPreview: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(com.armsx.flashpoint.data.CustomMedia.name(ctx, kind)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; note = null
            val r = withContext(Dispatchers.IO) { com.armsx.flashpoint.data.CustomMedia.set(ctx, kind, uri) }
            busy = false
            name = com.armsx.flashpoint.data.CustomMedia.name(ctx, kind)
            note = when (r) {
                com.armsx.flashpoint.data.CustomMedia.Result.OK -> { onChanged(); null }
                com.armsx.flashpoint.data.CustomMedia.Result.TOO_LARGE -> "That file is too big (up to ${kind.maxBytes / (1024 * 1024)} MB)."
                com.armsx.flashpoint.data.CustomMedia.Result.UNREADABLE -> "That file could not be read."
            }
        }
    }
    ConfigRow("Custom ${kind.label}", (if (busy) "Copying\u2026" else name?.let { "Using $it" } ?: desc) + (note?.let { "\n$it" } ?: "")) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Choose", false) { pick.launch(mime) }
            if (onPreview != null) Chip("Preview", false, onClick = onPreview)
            if (name != null) Chip("Reset", false) {
                com.armsx.flashpoint.data.CustomMedia.clear(ctx, kind); name = null; onChanged()
            }
        }
    }
}

@Composable
private fun ThemeMenu() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        Chip("${Fp.theme.value.name} \u25BE", false) { open = true }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FpThemes.all.forEach { t ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(Modifier.size(16.dp).background(t.browse).border(2.dp, t.accent))
                            Spacer(Modifier.width(10.dp))
                            Text(t.name)
                        }
                    },
                    onClick = { open = false; Fp.set(ctx, t) },
                )
            }
        }
    }
}

@Composable
private fun LogoSetMenu() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val sets = remember(open) { LogoSets.list(ctx) }
    Box {
        Chip("${LogoSets.current.value?.name ?: "No Logo Set"} \u25BE", false) { open = true }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("No Logo Set") }, onClick = { open = false; LogoSets.set(ctx, null) })
            sets.forEach { f ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(f.name) }, onClick = { open = false; LogoSets.set(ctx, f) })
            }
            if (sets.isEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text("No sets found in Data/LogoSets", color = Fp.TextDim) }, onClick = { open = false })
        }
    }
}

/** Every logo in the chosen set, as the desktop shows under its Logo Set row. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LogoPreview(set: java.io.File) {
    val files = remember(set) { set.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() } }
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        files.forEach { f -> coil.compose.AsyncImage(f, f.nameWithoutExtension, Modifier.size(36.dp)) }
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
        Text(title, color = Fp.Strong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(desc, color = Fp.TextDim, fontSize = 13.sp)
        PadTextBox(Modifier.fillMaxWidth().padding(top = 4.dp)) { m ->
            OutlinedTextField(v, { v = it; save(it) }, singleLine = true, modifier = m.fillMaxWidth())
        }
    }
}

/**
 * GPU driver for native Flash: ARMSX2's driver manager (DriverManagerSection) with its wording,
 * in the launcher's style. The GPU card and recommendation, system or installed drivers, the
 * download list grouped by source and filtered to this GPU, import, and Turnip options.
 * Used when Flash Player is Native and Graphics is Auto or Vulkan; the browser runner always
 * uses the phone's own driver.
 */
@Composable
private fun DriverSection(vm: BrowseVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cd = com.armsx.flashpoint.data.CustomDriver
    var installed by remember { mutableStateOf(cd.listInstalled(ctx)) }
    var selected by remember { mutableStateOf(cd.globalId().ifEmpty { null }) }
    var remote by remember { mutableStateOf<List<com.armsx.flashpoint.data.CustomDriver.RemoteDriver>?>(null) }
    var showRemote by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val expanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val gpu = remember { com.armsx.flashpoint.data.GpuInfo.rendererName() }
    val rec = remember(gpu) { com.armsx.flashpoint.data.GpuInfo.recommendation(gpu) }

    fun use(id: String?) { selected = id; cd.select(id) }
    fun refresh() { installed = cd.listInstalled(ctx) }

    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busyId = "import"; message = null
            val d = withContext(Dispatchers.IO) { runCatching { cd.installFromUri(ctx, uri) }.getOrNull() }
            busyId = null
            if (d != null) { refresh(); use(d.id); message = "Installed ${d.name}" } else message = "Couldn't import that file. It needs an AdrenoToolsDrivers-style .zip (meta.json + libvulkan_freedreno.so at the root)."
        }
    }

    Text(
        "Replace the system Vulkan driver with Mesa Turnip or another Adreno driver, for native Flash. " +
            "If a driver cannot load, the system driver is used.",
        color = Fp.TextDim, fontSize = 13.sp,
    )
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().background(Fp.Field, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(10.dp)) {
        Row { Text("GPU  ", color = Fp.TextDim, fontSize = 14.sp); Text(gpu ?: "Unknown", color = Fp.Play, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        Row { Text("Recommended  ", color = Fp.TextDim, fontSize = 14.sp); Text(rec?.sourceLabel ?: "System driver (built-in)", color = Fp.Play, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        rec?.reason?.let { Text(it, color = Fp.TextDim, fontSize = 12.sp) }
    }
    Spacer(Modifier.height(8.dp))
    DriverRow("System Vulkan driver", "Built into the device", selected == null, onUse = { use(null) })
    installed.forEach { d ->
        DriverRow(
            d.name, listOf(d.vendor, d.version).filter(String::isNotBlank).joinToString(" \u00B7 ").ifBlank { "Installed" },
            selected == d.id, onUse = { use(d.id) },
            onDelete = { cd.delete(d); if (selected == d.id) use(null); refresh() },
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(if (showRemote) "Hide downloads" else "Download", false) {
            showRemote = !showRemote
            if (showRemote && remote == null && !loading) {
                loading = true
                scope.launch {
                    remote = withContext(Dispatchers.IO) { runCatching { cd.fetchRemote(gpu) }.getOrDefault(emptyList()) }
                    loading = false
                }
            }
        }
        Chip("Import", false) { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    }
    if (showRemote) {
        Spacer(Modifier.height(6.dp))
        when {
            loading -> Text("Fetching\u2026", color = Fp.TextDim)
            remote.isNullOrEmpty() -> Text("No drivers found", color = Fp.TextDim)
            else -> remote!!.groupBy { it.source }.forEach { (source, drivers) ->
                val open = expanded[source] ?: false
                Row(
                    Modifier.fillMaxWidth().padFocus().clickable { expanded[source] = !open }.padding(vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(if (open) "\u25BE " else "\u25B8 ", color = Fp.TextDim)
                    Text(source, color = Fp.Strong, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${drivers.size}", color = Fp.TextDim, fontSize = 13.sp)
                }
                if (open) drivers.forEach { rd ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rd.title.ifBlank { rd.assetName }, color = Fp.Text, fontSize = 14.sp)
                            Text(listOf(rd.source, rd.tagName).filter(String::isNotBlank).joinToString(" \u00B7 "), color = Fp.TextDim, fontSize = 12.sp)
                        }
                        Chip(if (busyId == rd.id) "Installing\u2026" else "Get", false) {
                            if (busyId == null) scope.launch {
                                busyId = rd.id; message = null
                                val d = withContext(Dispatchers.IO) { runCatching { cd.download(ctx, rd) }.getOrNull() }
                                busyId = null
                                if (d != null) { refresh(); use(d.id); message = "Installed ${d.name}" } else message = "Download failed"
                            }
                        }
                    }
                }
            }
        }
    }
    if (busyId == "import") Text("Installing\u2026", color = Fp.TextDim)
    message?.let { Text(it, color = Fp.Play, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
    if (installed.isNotEmpty()) TurnipOptions()
}

@Composable
private fun DriverRow(title: String, subtitle: String, active: Boolean, onUse: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).padFocus(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(if (active) Fp.Play.copy(alpha = 0.15f) else Fp.Field, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onUse).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Fp.Strong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = Fp.TextDim, fontSize = 12.sp)
        }
        if (active) Text("Active", color = Fp.Play, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        if (onDelete != null) Chip("Delete", false, onDelete)
    }
}

/** ARMSX2's Turnip options: the UBWC flag hint and free driver variables. Global; read at the next game start. */
@Composable
private fun TurnipOptions() {
    val cd = com.armsx.flashpoint.data.CustomDriver
    var ubwc by remember { mutableStateOf(cd.ubwcFlagHint()) }
    var env by remember { mutableStateOf(cd.driverEnvText()) }
    Spacer(Modifier.height(10.dp))
    Text("Turnip options", color = Fp.Strong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    Text("Tuning read by Turnip drivers. The system driver ignores it. Changes apply at the next game start.", color = Fp.TextDim, fontSize = 13.sp)
    ConfigRow(
        "UBWC flag hint",
        "Can fix rendering glitches some recent Turnip builds show on certain Adreno GPUs. Leave it off unless you see them (FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1).",
    ) { Switch(ubwc, { ubwc = it; cd.setUbwcFlagHint(it) }) }
    PadTextBox(Modifier.fillMaxWidth()) { m ->
        OutlinedTextField(
            env, { env = it; cd.setDriverEnvText(it) },
            label = { Text("Driver variables") },
            placeholder = { Text("TU_DEBUG=sysmem") },
            supportingText = { Text("One NAME=value per line. FD_, TU_, IR3_ and MESA_ variables only. TU_DEBUG is read once, so restart the app after changing it.") },
            minLines = 2,
            modifier = m.fillMaxWidth(),
        )
    }
}

/** Discord Rich Presence, as in ARMSX2: connect once, then friends see what you are playing. */
@Composable
private fun DiscordSection() {
    val dp = com.armsx.flashpoint.discord.DiscordPresence
    val status by dp.status
    val err by dp.error
    if (!dp.available()) {
        Text("Discord isn't part of this build.", color = Fp.TextDim, fontSize = 13.sp)
        return
    }
    val statusText = when (status) {
        dp.CONNECTED -> "Connected" + (dp.self.value?.name?.let { " as $it" } ?: "")
        dp.AUTHORIZING -> "Waiting for Discord sign-in"
        dp.CONNECTING -> "Connecting"
        dp.FAILED -> "Couldn't connect"
        else -> "Not connected"
    }
    Text("Show friends on Discord what you are playing in FlashARM. Nothing is kept anywhere but a sign-in on this device.", color = Fp.TextDim, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    ConfigRow("Status", statusText + (err?.let { ". $it" } ?: "")) {
        if (status == dp.CONNECTED || status == dp.CONNECTING) Chip("Disconnect", false) { dp.signOut() }
        else Chip("Connect Discord", true) { dp.authorize() }
    }
    var showGame by remember { mutableStateOf(dp.showGameName) }
    ConfigRow("Show the game in my status", "Your status reads \u201cPlaying <game>\u201d instead of \u201cPlaying FlashARM\u201d.") {
        Switch(showGame, { showGame = it; dp.showGameName = it })
    }
}
