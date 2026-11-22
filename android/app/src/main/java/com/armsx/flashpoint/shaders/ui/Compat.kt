package com.armsx.flashpoint.shaders.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.armsx.flashpoint.ui.padFocus

/*
 * FlashARM's stand-ins for the ARMSX2 settings helpers its shader picker (ShaderChainSection,
 * ShaderParamsEditor) is written against, so those two files port with little change.
 */

/** ARMSX2's English strings for the picker (from its I18n table). */
private val STRINGS = mapOf(
    "renderer.shaderChain.params.namePlaceholder" to "Preset name…",
    "action.cancel" to "Cancel",
    "action.close" to "Close",
    "action.reset" to "Reset",
    "backend.driver.active" to "Active",
    "renderer.shaderChain.description" to "Runs a RetroArch (.slangp) shader chain over the final image: CRT masks, handheld LCD looks, scalers. Costs GPU time and stacks on top of the other display effects.",
    "renderer.shaderChain.empty" to "No .slangp presets found. Download a pack under Shader Packs below, or drop one into this folder by hand and reopen this list:",
    "renderer.shaderChain.folder.up" to "Up one folder",
    "renderer.shaderChain.label" to "RetroArch Shaders",
    "renderer.shaderChain.params.countModified" to "%d changed",
    "renderer.shaderChain.params.description" to "Tweak this preset's own settings",
    "renderer.shaderChain.params.empty" to "This preset doesn't expose any adjustable parameters.",
    "renderer.shaderChain.params.hint" to "D-Pad ↑↓ pick  ·  ←→ adjust  ·  A resets  ·  B closes",
    "renderer.shaderChain.params.label" to "Shader Parameters",
    "renderer.shaderChain.params.loading" to "Reading preset…",
    "renderer.shaderChain.params.resetAll" to "Reset all to preset defaults",
    "renderer.shaderChain.params.resetAll.confirmBody" to "Every parameter goes back to the preset's own defaults. Your changes here can't be undone. If you want to keep them, cancel and use \"Save as new preset\" first.",
    "renderer.shaderChain.params.resetAll.confirmTitle" to "Reset all parameters?",
    "renderer.shaderChain.params.saveAs" to "Save as new preset…",
    "renderer.shaderChain.pass" to "pass",
    "renderer.shaderChain.passes" to "passes",
    "renderer.shaderChain.passesUnknown" to "cost unknown",
    "renderer.shaderChain.preset.label" to "Shader Preset",
    "renderer.shaderChain.preset.none" to "None",
    "renderer.shaderChain.scanning" to "Scanning for presets…",
)

fun str(key: String): String = STRINGS[key] ?: key

object I18n { fun get(key: String): String = str(key) }

val Success = Color(0xFF66BB6A)

/** ARMSX2 moves its controller selection by id; FlashARM's focus follows the focused view. */
object SettingsControllerNav {
    fun currentSelectedId(): String? = null
    fun selectById(@Suppress("UNUSED_PARAMETER") id: String) {}
}

/** A focusable row: FlashARM's pad focus ring, and A / Enter / D-pad centre confirms. */
fun Modifier.controllerFocusable(
    @Suppress("UNUSED_PARAMETER") controllerId: String? = null,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    onConfirm: (() -> Unit)? = null,
): Modifier = this.padFocus(shape).onKeyEvent { e ->
    if (onConfirm != null && e.type == KeyEventType.KeyUp &&
        (e.key == Key.ButtonA || e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.NumPadEnter)
    ) { onConfirm(); true } else false
}

@Composable
fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)))
}

@Composable
fun HelpText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = modifier.padding(vertical = 4.dp))
}

@Composable
fun ToggleRow(
    label: String,
    value: Boolean,
    description: String? = null,
    @Suppress("UNUSED_PARAMETER") field: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onChange(!value) },
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padFocus(RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = value, onCheckedChange = null)
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color.copy(alpha = 0.35f))) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

/**
 * ARMSX2's on-screen keyboard, as the parameter editor uses it to name a saved preset: here a
 * plain text dialog ([LibraryKeyboardHost] shows it).
 */
object LibraryKeyboard {
    val visible = mutableStateOf(false)
    val text = mutableStateOf("")
    var placeholder = ""
    fun open(initial: String, @Suppress("UNUSED_PARAMETER") onChange: (String) -> Unit, placeholder: String = "") {
        text.value = initial; this.placeholder = placeholder; visible.value = true
    }
    fun close() { visible.value = false }
}

@Composable
fun LibraryKeyboardHost() {
    if (!LibraryKeyboard.visible.value) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { LibraryKeyboard.text.value = ""; LibraryKeyboard.close() },
        title = { Text(LibraryKeyboard.placeholder) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                LibraryKeyboard.text.value, { LibraryKeyboard.text.value = it }, singleLine = true,
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { LibraryKeyboard.close() }) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { LibraryKeyboard.text.value = ""; LibraryKeyboard.close() }) { Text(str("action.cancel")) } },
    )
}
