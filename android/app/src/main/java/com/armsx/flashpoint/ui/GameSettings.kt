package com.armsx.flashpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx.flashpoint.data.CustomDriver
import com.armsx.flashpoint.game.FlashPrefs
import com.armsx.flashpoint.shaders.ShaderPrefs

/**
 * A game's own settings, from the library without starting it: hold the game's tile, or Game
 * Settings in its details. A pick that keeps a game from starting (a GPU driver, a player or a
 * shader that does not run on this phone) can be changed or reset here, since the pause menu that
 * set it is out of reach while the game cannot start. Each row steps through its choices.
 */
@Composable
fun GameSettingsDialog(gameId: String, title: String, flash: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val drivers = remember {
        listOf("" to "Same as Config", CustomDriver.SYSTEM_FOR_GAME to "System driver") +
            CustomDriver.listInstalled(ctx).map { it.id to it.name }
    }
    var driver by remember { mutableStateOf(CustomDriver.gameId(gameId)) }
    var player by remember { mutableStateOf(FlashPrefs.gamePlayer(ctx, gameId)) }
    var backend by remember { mutableStateOf(FlashPrefs.gameBackend(ctx, gameId)) }
    var shader by remember { mutableStateOf(ShaderPrefs.gamePreset(ctx, gameId)) }
    var scale by remember { mutableStateOf(ShaderPrefs.gameScale(ctx, gameId)) }
    // The game's own preset stays a choice, so stepping past it can come back to it.
    val shaders = remember {
        listOf("" to "Same as Config", ShaderPrefs.OFF to "Off") +
            listOfNotNull(shader.takeIf { it.isNotEmpty() && it != ShaderPrefs.OFF }?.let { it to it.substringAfterLast('/').removeSuffix(".slangp") })
    }
    val scales = listOf(0 to "Same as Config") + ShaderPrefs.SCALES.map { it to "$it%" }

    fun setDriver(v: String) { driver = v; CustomDriver.selectForGame(gameId, v) }
    fun setPlayer(v: String) { player = v; FlashPrefs.setGamePlayer(ctx, gameId, v) }
    fun setBackend(v: String) { backend = v; FlashPrefs.setGameBackend(ctx, gameId, v) }
    fun setShader(v: String) { shader = v; ShaderPrefs.setGamePreset(ctx, gameId, v) }
    fun setScale(v: Int) { scale = v; ShaderPrefs.setGameScale(ctx, gameId, v) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("This game's own settings. Same as Config follows the choice in Config. Tap a row to change it.", color = Fp.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                SettingRow("GPU driver", drivers, driver, ::setDriver)
                if (flash) {
                    SettingRow("Player", FlashPrefs.players, player, ::setPlayer)
                    SettingRow("Graphics", FlashPrefs.backends, backend, ::setBackend)
                    SettingRow("Shader", shaders, shader, ::setShader)
                    SettingRow("Shader Resolution", scales, scale, ::setScale)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.padFocus(RoundedCornerShape(20.dp))) { Text("Done") }
        },
        dismissButton = {
            TextButton(
                onClick = { setDriver(""); setPlayer(""); setBackend(""); setShader(""); setScale(0) },
                modifier = Modifier.padFocus(RoundedCornerShape(20.dp)),
            ) { Text("Reset all") }
        },
    )
}

/** A setting and its current choice; a tap moves to the next choice. */
@Composable
private fun <T> SettingRow(label: String, choices: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    val i = choices.indexOfFirst { it.first == value }
    // A choice no longer offered (a deleted driver) works as Same as Config and reads so.
    val shown = choices.getOrNull(i)?.second ?: choices.first().second
    Row(
        Modifier.fillMaxWidth().padFocus(RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(Fp.Field)
            .clickable { onChange(choices[(i + 1).mod(choices.size)].first) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(shown, color = if (i > 0) Fp.Accent else Fp.TextDim, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
