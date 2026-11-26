package com.armsx.flashpoint.game

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx.flashpoint.ui.Chip
import com.armsx.flashpoint.ui.ConfigRow
import com.armsx.flashpoint.ui.Fp
import com.armsx.flashpoint.ui.sliderPadKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/*
 * LSFG frame generation's settings, ported from ARMSX2's LsfgSection (ui/common/LsfgSection.kt,
 * ARMSX2 team) with its wording: a switch, the multiplier, adaptive pacing, Half Precision, Motion
 * Detail, the Lossless.dll picker, and why it cannot run when it cannot. FrameGenCard is the
 * pause menu's (native Flash), FrameGenConfig is Config's; both edit FrameGenPrefs.
 *
 * GitHub build only. src/play has stand-ins of the same names, so the Play build carries neither
 * these rows nor their words, and it has no libarmflash_lsfg.so either.
 *
 * Nothing proprietary ships with ARMFlash: the interpolation shaders are read at run time out of
 * the user's own Lossless.dll, picked here and copied into the app's files.
 */

private object T {
    const val LABEL = "Frame Generation (LSFG)"
    const val DESCRIPTION = "Insert interpolated frames between rendered ones. Native Flash only. Needs Vulkan, an Adreno 7xx or newer GPU (or a 6xx running a Turnip driver), and your own copy of Lossless Scaling."
    const val MULTIPLIER = "Generated Frames"
    const val MULTIPLIER_DESCRIPTION = "Frames displayed for each frame the game renders. Higher is smoother but adds latency and GPU load. A game that already runs at the screen's rate gets no extra frames."
    const val FP16 = "Half Precision"
    const val FP16_DESCRIPTION = "Use the half-precision (fp16) shaders, which phone GPUs often run much faster. Some drivers handle fp16 badly: if generated frames look wrong, turn this off. Turning it on takes effect the next time a game starts, and the performance overlay shows fp16 while it is in use."
    const val ADAPTIVE = "Adaptive frame pacing"
    const val ADAPTIVE_DESCRIPTION = "Vary how many frames are generated to hold a steady on-screen rate, instead of always multiplying by the same amount. Helps most in games whose frame rate moves. Targets your display’s refresh rate."
    const val MOTION = "Motion Detail"
    const val MOTION_DESCRIPTION = "Resolution of the motion analysis. At Auto it follows the screen at the detail the movement needs. Lower is much cheaper and blurs fine detail in the generated frames."
    const val MOTION_AUTO = "Auto"
    const val DLL = "Lossless.dll"
    const val DLL_NONE = "Not selected. Tap to choose your Lossless.dll"
    const val DLL_SELECTED = "Selected. Tap to choose a different file"
    const val DLL_IMPORT_FAILED = "Could not read the selected file."
    const val DLL_NOT_A_DLL = "That file is not a readable Lossless.dll."
    const val REQUIREMENTS_TITLE = "Frame Generation requirements"
    const val REQUIREMENTS_BODY = "Frame generation uses Lossless Scaling’s own interpolation shaders. ARMFlash does not include them: you must own Lossless Scaling and supply its Lossless.dll yourself (in your Steam folder, steamapps/common/Lossless Scaling).\n\nIt runs on native Flash with the Vulkan renderer, on an Adreno 7xx or newer GPU, or an Adreno 6xx running a Turnip driver. On anything else it stays off."
    const val REQUIREMENTS_ACCEPT = "I understand"
    const val CANCEL = "Cancel"
    const val STATUS = "Status"
    const val NOT_BUILT = "Frame generation is not included in this build."
    const val NOT_VULKAN = "Frame generation requires the Vulkan renderer. Set Graphics to Vulkan or Auto for this game."
    const val GPU = "Frame generation requires an Adreno 7xx or newer GPU, or an Adreno 6xx running a Turnip driver."
    const val NO_DLL = "Select your Lossless.dll to enable frame generation."
    const val BAD_DLL = "The selected file is not a readable Lossless.dll."
    const val INIT_FAILED = "Frame generation could not start on this device. Check the log for details."
    const val CONFIG_NOTE = "For every native Flash game. The pause menu changes the same settings while a game runs."
}

private fun reasonText(reason: Int): String = when (reason) {
    FrameGenPrefs.Reason.AVAILABLE -> ""
    FrameGenPrefs.Reason.NOT_COMPILED_IN -> T.NOT_BUILT
    FrameGenPrefs.Reason.NOT_VULKAN -> T.NOT_VULKAN
    FrameGenPrefs.Reason.GPU_UNSUPPORTED -> T.GPU
    FrameGenPrefs.Reason.NO_DLL -> T.NO_DLL
    FrameGenPrefs.Reason.DLL_UNREADABLE -> T.BAD_DLL
    else -> T.INIT_FAILED
}

/** Where an imported Lossless.dll lives: copied out of the pick, because the native side reads it as a file. */
private fun dllTarget(ctx: Context): File = File(File(ctx.filesDir, "lsfg").apply { mkdirs() }, "Lossless.dll")

/** Structural only (ARMSX2's LooksLikeLosslessDll): "MZ", and a PE signature where the DOS header points. */
private fun looksLikeDll(f: File): Boolean = runCatching {
    RandomAccessFile(f, "r").use { r ->
        val dos = ByteArray(0x40)
        r.readFully(dos)
        if (dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return@use false
        val pe = (dos[0x3C].toLong() and 0xff) or ((dos[0x3D].toLong() and 0xff) shl 8) or
            ((dos[0x3E].toLong() and 0xff) shl 16) or ((dos[0x3F].toLong() and 0xff) shl 24)
        if (pe < 0x40 || pe > 64L * 1024 * 1024 || pe + 4 > r.length()) return@use false
        r.seek(pe)
        val sig = ByteArray(4)
        r.readFully(sig)
        sig[0] == 'P'.code.toByte() && sig[1] == 'E'.code.toByte() && sig[2] == 0.toByte() && sig[3] == 0.toByte()
    }
}.getOrDefault(false)

/**
 * Copies the pick into the app's files and checks it. Null on success, else what went wrong.
 * The byte count, not just "no exception": a provider handing back an empty stream copies zero
 * bytes without failing.
 */
private fun importDll(ctx: Context, uri: Uri): String? {
    val target = dllTarget(ctx)
    val tmp = File(target.path + ".tmp")
    val copied = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: 0L
    }.getOrDefault(0L) > 0L
    if (!copied) { tmp.delete(); return T.DLL_IMPORT_FAILED }
    if (!looksLikeDll(tmp)) { tmp.delete(); return T.DLL_NOT_A_DLL }
    if (!tmp.renameTo(target)) { tmp.delete(); return T.DLL_IMPORT_FAILED }
    return null
}

/** The pause menu's frame generation card (native Flash). Changes apply at once, except Half Precision. */
@Composable
fun FrameGenCard(host: OverlayHost) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(FrameGenPrefs.load(ctx)) }
    var showRequirements by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    // Asked again on every change: it moves with the file just picked and the switch just thrown.
    var reason by remember { mutableStateOf(host.frameGenReason()) }
    var status by remember { mutableStateOf(host.frameGenStatus()) }

    fun set(n: FrameGenSettings) {
        s = n
        FrameGenPrefs.save(ctx, n)
        host.frameGenChanged()
        scope.launch {
            kotlinx.coroutines.delay(400) // the player applies it on its own thread
            reason = host.frameGenReason()
            status = host.frameGenStatus()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) { importDll(ctx, uri) }
            importError = err
            // Same path as before when one was already picked, new bytes behind it: set() hands
            // it over again either way, and the library reads the file afresh.
            if (err == null) set(s.copy(dll = dllTarget(ctx).path))
        }
    }

    SectionCard(T.LABEL) {
        Text(T.DESCRIPTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        // The requirements fire on the way ON only, and before the switch commits.
        MenuSwitchRow(T.LABEL, s.enabled) { on -> if (on) showRequirements = true else set(s.copy(enabled = false)) }
        if (showRequirements) {
            Spacer(Modifier.height(6.dp))
            Requirements(
                onDismiss = { showRequirements = false },
                onAccept = {
                    showRequirements = false
                    set(s.copy(enabled = true))
                    // Straight into the picker when there is nothing to run against.
                    if (s.dll.isBlank()) picker.launch(arrayOf("*/*"))
                },
            )
        }
        if (s.enabled) {
            Spacer(Modifier.height(8.dp))
            Text(T.MULTIPLIER, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 3, 4).forEach { m -> OptionChip("x$m", s.multiplier == m) { set(s.copy(multiplier = m)) } }
            }
            Hint(T.MULTIPLIER_DESCRIPTION)
            Spacer(Modifier.height(6.dp))
            MenuSwitchRow(T.ADAPTIVE, s.adaptive) { set(s.copy(adaptive = it)) }
            Hint(T.ADAPTIVE_DESCRIPTION)
            Spacer(Modifier.height(6.dp))
            MenuSwitchRow(T.FP16, s.fp16) { set(s.copy(fp16 = it)) }
            Hint(T.FP16_DESCRIPTION)
            Spacer(Modifier.height(6.dp))
            var motion by remember(s.motion) { mutableStateOf(s.motion) }
            Text("${T.MOTION}  " + if (motion >= 100) T.MOTION_AUTO else "$motion%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Slider(
                motion.toFloat(), { motion = it.roundToInt() }, valueRange = 25f..100f,
                onValueChangeFinished = { set(s.copy(motion = motion)) },
                modifier = Modifier.sliderPadKeys().menuFocus(RoundedCornerShape(12.dp)),
            )
            Hint(T.MOTION_DESCRIPTION)
            Spacer(Modifier.height(6.dp))
            MenuCycleRow(T.DLL, importError ?: if (s.dll.isBlank()) T.DLL_NONE else T.DLL_SELECTED) { picker.launch(arrayOf("*/*")) }
            // Say WHY, not just "off": only some of these are something the user can act on.
            val why = reasonText(reason)
            if (why.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else if (status.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("${T.STATUS}: $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * What the user has to own and what hardware this needs, shown before the switch commits. Inline,
 * not a dialog: a dialog is its own window and would strand a controller user (ARMSX2's reason).
 */
@Composable
private fun Requirements(onDismiss: () -> Unit, onAccept: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(T.REQUIREMENTS_TITLE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(T.REQUIREMENTS_BODY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { MenuCycleRow(T.CANCEL, "", onDismiss) }
                Column(Modifier.weight(1f)) { MenuCycleRow(T.REQUIREMENTS_ACCEPT, "", onAccept) }
            }
        }
    }
}

/** Config's frame generation section: the same settings, for every native Flash game. */
@Composable
fun FrameGenConfig() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(FrameGenPrefs.load(ctx)) }
    var showRequirements by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    fun set(n: FrameGenSettings) { s = n; FrameGenPrefs.save(ctx, n) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) { importDll(ctx, uri) }
            importError = err
            if (err == null) set(s.copy(dll = dllTarget(ctx).path))
        }
    }

    Text(T.CONFIG_NOTE, color = Fp.TextDim, fontSize = 12.sp)
    ConfigRow(T.LABEL, T.DESCRIPTION) {
        Switch(s.enabled, { on -> if (on) showRequirements = true else set(s.copy(enabled = false)) })
    }
    if (showRequirements) {
        Surface(shape = RoundedCornerShape(10.dp), color = Fp.Accent.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(T.REQUIREMENTS_TITLE, color = Fp.Strong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(T.REQUIREMENTS_BODY, color = Fp.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(T.CANCEL, false) { showRequirements = false }
                    Chip(T.REQUIREMENTS_ACCEPT, true) {
                        showRequirements = false
                        set(s.copy(enabled = true))
                        if (s.dll.isBlank()) picker.launch(arrayOf("*/*"))
                    }
                }
            }
        }
    }
    if (!s.enabled) return
    ConfigRow(T.MULTIPLIER, T.MULTIPLIER_DESCRIPTION) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(2, 3, 4).forEach { m -> Chip("x$m", s.multiplier == m) { set(s.copy(multiplier = m)) } }
        }
    }
    ConfigRow(T.ADAPTIVE, T.ADAPTIVE_DESCRIPTION) { Switch(s.adaptive, { set(s.copy(adaptive = it)) }) }
    ConfigRow(T.FP16, T.FP16_DESCRIPTION) { Switch(s.fp16, { set(s.copy(fp16 = it)) }) }
    var motion by remember(s.motion) { mutableStateOf(s.motion) }
    ConfigRow(T.MOTION + "  " + if (motion >= 100) T.MOTION_AUTO else "$motion%", T.MOTION_DESCRIPTION) {
        Slider(
            motion.toFloat(), { motion = it.roundToInt() }, valueRange = 25f..100f,
            onValueChangeFinished = { set(s.copy(motion = motion)) },
            modifier = Modifier.sliderPadKeys(),
        )
    }
    ConfigRow(T.DLL, importError ?: if (s.dll.isBlank()) T.DLL_NONE else T.DLL_SELECTED) {
        Chip(if (s.dll.isBlank()) "Choose" else "Change", false) { picker.launch(arrayOf("*/*")) }
    }
}
