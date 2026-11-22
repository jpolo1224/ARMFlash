package com.armsx.flashpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "Back", tint = Fp.Text) }
            Column(Modifier.weight(1f)) {
                Text(g.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                if (g.developer.isNotBlank()) Text("by ${g.developer}", color = Fp.TextDim, fontSize = 13.sp)
            }
            val fav = g.id in vm.favorites
            IconButton(onClick = { vm.toggleFavorite(g.id) }) {
                Icon(if (fav) Icons.Filled.Star else Icons.Outlined.Star, "Favorite", tint = if (fav) Fp.Accent else Fp.TextDim)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                full == null -> ""
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
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(4.dp)).background(color)
                .clickable(enabled = full != null && dl == null) { onPlay(full!!) },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
        }
        if (dl != null && dl.total > 0) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Fp.Field)) {
                Box(Modifier.fillMaxWidth(dl.done.toFloat() / dl.total).height(4.dp).background(Fp.Play))
            }
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
        AsyncImage(
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
