package com.armsx.flashpoint.ui

import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.armsx.flashpoint.core.Game
import com.armsx.flashpoint.game.Launch

/** The launcher's right-hand game panel. */
@Composable
fun Details(vm: BrowseVm, game: Game, onPlay: (Game) -> Unit, onClose: (() -> Unit)?) {
    val full = vm.details?.takeIf { it.id == game.id }
    val g = full ?: game
    val dl = vm.downloads[g.id]
    val installed = full != null && (full.activeData == null || vm.isInstalled(full))
    val plan = full?.let { Launch.plan(it) }
    // The player's own .swf (My Games): no Flashpoint entry, so no favorite, pack or shortcut.
    val mine = com.armsx.flashpoint.data.LocalGames.isLocal(g.id)

    Column(Modifier.fillMaxSize().padVerticalScroll().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.padFocus(RoundedCornerShape(20.dp))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Fp.Text) }
            Column(Modifier.weight(1f)) {
                Text(g.title, color = Fp.Strong, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                if (g.developer.isNotBlank()) Text("by ${g.developer}", color = Fp.TextDim, fontSize = 13.sp)
            }
            val fav = g.id in vm.favorites
            if (!mine) IconButton(onClick = { vm.toggleFavorite(g.id) }, modifier = Modifier.padFocus(RoundedCornerShape(20.dp))) {
                Icon(if (fav) Icons.Filled.Star else Icons.Outlined.Star, "Favorite", tint = if (fav) Fp.Accent else Fp.TextDim)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                full == null -> ""
                mine -> "My Games"
                full.activeData == null -> "Legacy"
                installed -> "Installed"
                else -> "Not installed"
            },
            color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))

        // Play / download button, like the launcher's big green bar.
        val (label, color) = when {
            dl != null -> {
                val pct = if (dl.total > 0) (dl.done * 100 / dl.total) else 0
                "Downloading $pct%" to Fp.Download
            }
            full == null -> "Loading" to Fp.PlayDark
            plan != null && !plan.runner.ready -> "${plan.runner.label}: coming soon" to Color(0xFF666666)
            installed -> "Play" to Fp.Play
            else -> "Download & Play" to Fp.Download
        }
        Box(
            Modifier.fillMaxWidth().height(52.dp).padFocus(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)).background(color)
                .clickable(enabled = full != null && dl == null) { onPlay(full!!) },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, maxLines = 1)
        }
        // Not downloaded yet: Download fetches it without starting it, for getting many games in a
        // row. A row like the ones under it, so the big bar keeps the whole width.
        if (full != null && dl == null && !installed && (plan == null || plan.runner.ready)) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(38.dp).padFocus(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
                    .background(Fp.Field).clickable { vm.download(full) },
                contentAlignment = Alignment.Center,
            ) {
                Text("Download without playing", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        // Playlists: put the game in one of yours, or a new one.
        if (full != null && !mine) {
            var picking by remember { mutableStateOf(false) }
            var naming by remember { mutableStateOf(false) }
            Spacer(Modifier.height(6.dp))
            val inCount = vm.playlists.count { full.id in it.gameIds }
            Box(
                Modifier.fillMaxWidth().height(38.dp).padFocus(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
                    .background(Fp.Field).clickable { picking = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (inCount > 0) "Playlists ($inCount)" else "Add to Playlist", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            if (picking) {
                val mineLists = vm.playlists.filter { it.library == vm.lib }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { picking = false },
                    title = { Text("Playlists") },
                    text = {
                        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                            if (mineLists.isEmpty()) Text("No playlists yet.", color = Fp.TextDim)
                            mineLists.forEach { pl ->
                                val on = full.id in pl.gameIds
                                Row(
                                    Modifier.fillMaxWidth().padFocus(RoundedCornerShape(6.dp)).clickable { vm.togglePlaylist(pl, full) }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (on) "\u2713" else "", color = Fp.Play, fontWeight = FontWeight.Black, modifier = Modifier.width(24.dp))
                                    Text(pl.title, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { picking = false; naming = true }, modifier = Modifier.padFocus(RoundedCornerShape(20.dp))) { Text("New Playlist") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { picking = false }, modifier = Modifier.padFocus(RoundedCornerShape(20.dp))) { Text("Done") }
                    },
                )
            }
            if (naming) PlaylistNameDialog(onDismiss = { naming = false }) { name -> naming = false; vm.createPlaylist(name, first = full) }
        }
        if (dl != null && dl.total > 0) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Fp.Field)) {
                Box(Modifier.fillMaxWidth(dl.done.toFloat() / dl.total).height(4.dp).background(Fp.Play))
            }
        }

        // Uninstall: only data packs ARMFlash downloaded (legacy games have nothing to remove).
        val pack = full?.activeData
        if (installed && pack != null && dl == null) {
            var confirm by remember { mutableStateOf(false) }
            val mb = (pack.size + (1 shl 19)) / (1 shl 20)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(38.dp).padFocus(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
                    .background(Fp.Field).clickable { confirm = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("Uninstall  ($mb MB)", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            if (confirm) androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Uninstall ${g.title}?") },
                text = { Text("This frees $mb MB. Your play history stays, and you can download it again any time.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { confirm = false; vm.uninstall(full) },
                        modifier = Modifier.padFocus(RoundedCornerShape(20.dp)),
                    ) { Text("Uninstall") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { confirm = false },
                        modifier = Modifier.padFocus(RoundedCornerShape(20.dp)),
                    ) { Text("Cancel") }
                },
            )
        }

        if (mine && full != null) MyGameActions(vm, full)

        // Add to home screen: a launcher shortcut with the game's own art.
        if (full != null && !mine && com.armsx.flashpoint.HomeShortcuts.isSupported(LocalContext.current)) {
            val ctx = LocalContext.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(38.dp).padFocus(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
                    .background(Fp.Field).clickable {
                        scope.launch {
                            val ok = com.armsx.flashpoint.HomeShortcuts.pin(ctx, full.id, full.title, vm.library.logoUrl(full.id))
                            if (!ok) vm.status = "Your launcher did not accept the shortcut."
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("Add to home screen", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Last Played", g.lastPlayed?.take(10) ?: "Never")
            Stat("Playtime", playtime(g.playtime))
            Stat("Plays", g.playCounter.toString())
        }
        Spacer(Modifier.height(10.dp))

        Field("Alternate Titles", g.alternateTitles)
        if (g.tagList.isNotEmpty()) {
            Text("Tags:", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            g.tagList.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, top = 1.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Fp.Tag))
                    Spacer(Modifier.size(6.dp))
                    Text(t, color = Fp.Text, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Field("Series", g.series)
        Field("Publisher", g.publisher)
        Field("Source", g.source)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Platform: ", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            PlatformBadge(g.primaryPlatform, Modifier.size(18.dp))
            Text(" ${g.primaryPlatform}", color = Fp.Text, fontSize = 13.sp)
        }
        if (plan != null) Field("Runs with", plan.runner.label)
        g.activeData?.takeIf { it.size > 0 }?.let { gd ->
            Field("Data", sizeText(gd.size) + if (vm.library.zipFor(gd).isFile) " (downloaded)" else "")
        }
        Field("Play Mode", g.playMode)
        Field("Status", g.status)
        Field("Version", g.version)
        Field("Language", g.language)
        Field("Ruffle Support", g.ruffleSupport.ifBlank { "None" })
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Date Added", g.dateAdded.take(10))
            Stat("Date Modified", g.dateModified.take(10))
            Stat("Release", g.releaseDate.ifBlank { "-" })
        }
        if (g.notes.isNotBlank()) Field("Notes", g.notes)
        if (g.originalDescription.isNotBlank()) {
            Text("Original Description:", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(g.originalDescription, color = Fp.Text, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }
        // Hide Extreme Screenshots: an Extreme game's screenshot shows only when asked, as in the
        // launcher.
        var showShot by remember(g.id) { mutableStateOf(false) }
        if (vm.prefs2.hideExtremeScreenshots.value && com.armsx.flashpoint.core.Extreme.isExtreme(g) && !showShot) {
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Screenshot hidden (Extreme).", color = Fp.TextDim, fontSize = 13.sp)
                Spacer(Modifier.size(8.dp))
                Chip("Show Screenshot", on = false) { showShot = true }
            }
        } else AsyncImage(
            model = vm.library.screenshotUrl(g.id), contentDescription = "Screenshot",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun Field(name: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$name: ", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(value, color = Fp.Text, fontSize = 13.sp)
    }
}

@Composable
private fun Stat(name: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(value, color = Fp.TextDim, fontSize = 12.sp)
    }
}

private fun playtime(s: Long): String = when {
    s <= 0 -> "Never"
    s < 3600 -> "${s / 60} Minutes"
    else -> "%.1f Hours".format(s / 3600.0)
}

/** Rename and Remove for a game in My Games. */
@Composable
private fun MyGameActions(vm: BrowseVm, g: Game) {
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Rename" to { renaming = true }, "Remove" to { removing = true }).forEach { (label, onClick) ->
            Box(
                Modifier.weight(1f).height(38.dp).padFocus(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
                    .background(Fp.Field).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(g.title) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { renaming = false; vm.renameLocal(g.id, name) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.padFocus(RoundedCornerShape(20.dp)),
                ) { Text("Save") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renaming = false }, modifier = Modifier.padFocus(RoundedCornerShape(20.dp))) { Text("Cancel") }
            },
        )
    }
    if (removing) androidx.compose.material3.AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("Remove ${g.title}?") },
        text = { Text("It leaves My Games, and ARMFlash's copy of the file is deleted. The file you added it from stays where it is.") },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { removing = false; vm.removeLocal(g.id) },
                modifier = Modifier.padFocus(RoundedCornerShape(20.dp)),
            ) { Text("Remove") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { removing = false }, modifier = Modifier.padFocus(RoundedCornerShape(20.dp))) { Text("Cancel") }
        },
    )
}
