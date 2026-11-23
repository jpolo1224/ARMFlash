package com.armsx.flashpoint.update

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.armsx.flashpoint.BuildConfig
import com.armsx.flashpoint.ui.Chip
import com.armsx.flashpoint.ui.ConfigRow
import com.armsx.flashpoint.ui.padFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater for the GitHub build, ported from ARMSX3's (itself ARMSX2's) UpdaterEntry.
 *
 * Config's Updates section: asks GitHub for ARMFlash's latest release, compares its tag with the
 * installed version, downloads the release's .apk with a progress bar into
 * externalCacheDir/updates/, and hands it to the system package installer through a FileProvider.
 * The player confirms the install; nothing is installed silently. "Check on launch" does the same
 * check once at start ([AutoUpdateGate]) and speaks up only when there is a new version.
 *
 * A SELF-UPDATING APP IS A HARD PLAY-POLICY VIOLATION, and Play rejects the REQUEST_INSTALL_PACKAGES
 * permission itself, not just code that uses it. So this file, the permission and the provider
 * live in src/github only; src/play has empty stand-ins, as in ARMSX2.
 */

private val LATEST_URL = "https://api.github.com/repos/${com.armsx.flashpoint.data.News.REPO}/releases/latest"
private const val PREF_ON_LAUNCH = "update.checkOnLaunch"

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val notes: String, val apkUrl: String) : UpdateState
    data class Downloading(val pct: Int) : UpdateState
    data class Error(val msg: String) : UpdateState
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

/** Config's Updates section: the version, Check for Updates and Check on Launch. */
@Composable
fun UpdaterRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val line = when (val s = state) {
        is UpdateState.Checking -> "Checking for updates…"
        is UpdateState.UpToDate -> "You have the latest version."
        is UpdateState.Downloading -> "Downloading the update… ${s.pct}%"
        is UpdateState.Error -> s.msg
        else -> ""
    }
    ConfigRow("ARMFlash ${BuildConfig.VERSION_NAME}", listOf("New versions come from ARMFlash's GitHub releases.", line).filter { it.isNotEmpty() }.joinToString(" ")) {
        Chip("Check for Updates", on = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
            if (state is UpdateState.Checking || state is UpdateState.Downloading) return@Chip
            scope.launch {
                state = UpdateState.Checking
                state = checkForUpdate()
            }
        }
    }
    var onLaunch by remember { mutableStateOf(prefs(context).getBoolean(PREF_ON_LAUNCH, false)) }
    ConfigRow("Check on Launch", "Look for a new version each time ARMFlash starts, and offer it when there is one.") {
        Switch(onLaunch, { onLaunch = it; prefs(context).edit().putBoolean(PREF_ON_LAUNCH, it).apply() })
    }
    UpdateDialog(state, onState = { state = it })
}

/**
 * Mounted once at the app's root: with Check on Launch on, one quiet check at start that offers the
 * update only when there is one (no "up to date" popup, no errors). Same download and install as
 * the button.
 */
@Composable
fun AutoUpdateGate() {
    val context = LocalContext.current
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    LaunchedEffect(Unit) {
        if (prefs(context).getBoolean(PREF_ON_LAUNCH, false)) {
            val result = checkForUpdate()
            if (result is UpdateState.Available) state = result
        }
    }
    if (state is UpdateState.Available || state is UpdateState.Downloading) UpdateDialog(state, onState = { state = it })
}

/** The offer: the new version's notes, then Download and Install with its progress. */
@Composable
private fun UpdateDialog(state: UpdateState, onState: (UpdateState) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avail = state as? UpdateState.Available
    if (avail == null && state !is UpdateState.Downloading) return
    AlertDialog(
        onDismissRequest = { if (state !is UpdateState.Downloading) onState(UpdateState.Idle) },
        title = { Text(if (avail != null) "ARMFlash ${avail.version} is available" else "Downloading the update") },
        text = {
            when (state) {
                is UpdateState.Available -> Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(plainNotes(state.notes).ifBlank { "This release has no notes." }, fontSize = 13.sp)
                }
                is UpdateState.Downloading -> Column {
                    Text("${state.pct}%", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { state.pct / 100f }, modifier = Modifier.fillMaxWidth())
                }
                else -> {}
            }
        },
        confirmButton = {
            if (avail != null) TextButton(
                onClick = {
                    scope.launch {
                        onState(UpdateState.Downloading(0))
                        try {
                            downloadAndInstall(context, avail) { pct -> onState(UpdateState.Downloading(pct)) }
                            onState(UpdateState.Idle)
                        } catch (e: Exception) {
                            onState(UpdateState.Error("Could not download the update: ${e.message}"))
                        }
                    }
                },
                modifier = Modifier.padFocus(RoundedCornerShape(20.dp)),
            ) { Text("Download and Install") }
        },
        dismissButton = {
            if (avail != null) TextButton(onClick = { onState(UpdateState.Idle) }, modifier = Modifier.padFocus(RoundedCornerShape(20.dp))) { Text("Later") }
        },
    )
}

/**
 * Release notes are Markdown with pictures (HTML img tags); the dialog shows their text only, the
 * News tab shows the pictures.
 */
private fun plainNotes(md: String): String = md.replace(Regex("""<[^>]+>"""), "").lines().mapNotNull { raw ->
    val l = raw.trimEnd()
    when {
        Regex("""^\s*\|?\s*:?-{3,}""").containsMatchIn(l) -> null
        l.trimStart().startsWith("|") -> l.trim().trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(": ")
        else -> l.replace(Regex("""^#+\s*"""), "").replace(Regex("""^(\s*)- """), "$1• ")
    }
}.joinToString("\n").replace("**", "").replace("`", "").replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
    .replace(Regex("""\n\s*\n(\s*\n)+"""), "\n\n").trim()

private suspend fun checkForUpdate(): UpdateState = withContext(Dispatchers.IO) {
    try {
        val obj = JSONObject(httpGet(LATEST_URL))
        val apkUrl = pickApkAsset(obj) ?: return@withContext UpdateState.UpToDate
        val tag = obj.getString("tag_name")
        if (isNewer(tag, BuildConfig.VERSION_NAME)) UpdateState.Available(tag, obj.optString("body", ""), apkUrl)
        else UpdateState.UpToDate
    } catch (e: Exception) {
        UpdateState.Error("Could not check for updates: ${e.message}")
    }
}

/** The release's .apk (its .tar.zst files are the Windows support downloads, not the app). */
private fun pickApkAsset(release: JSONObject): String? {
    val assets = release.optJSONArray("assets") ?: return null
    for (i in 0 until assets.length()) {
        val a = assets.getJSONObject(i)
        if (a.getString("name").endsWith(".apk", ignoreCase = true)) return a.getString("browser_download_url")
    }
    return null
}

/** Release tag vs the installed versionName, by their leading dotted numbers ("v1.0.1" > "1.0"). */
internal fun isNewer(remoteTag: String, installed: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.', '-')
        .map { it.takeWhile(Char::isDigit) }.mapNotNull { it.toIntOrNull() }
    val r = parts(remoteTag); val i = parts(installed)
    for (k in 0 until maxOf(r.size, i.size)) {
        val a = r.getOrElse(k) { 0 }; val b = i.getOrElse(k) { 0 }
        if (a != b) return a > b
    }
    return false
}

private fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ARMFlash-Updater")
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private suspend fun downloadAndInstall(context: Context, info: UpdateState.Available, onProgress: (Int) -> Unit) {
    val apk = withContext(Dispatchers.IO) {
        val dir = File(context.externalCacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // keep only the current download
        val out = File(dir, "armflash-update.apk")
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "ARMFlash-Updater")
            val total = conn.contentLengthLong
            var read = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        out
    }
    // Hand the APK to the system package installer (the player confirms the install). Straight to
    // it: ARMFlash opens any file (for .armflash and .swf files), so a plain VIEW would offer
    // ARMFlash itself next to the installer.
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.packageManager.queryIntentActivities(intent, 0)
        .firstOrNull { it.activityInfo.packageName != context.packageName }
        ?.let { intent.setClassName(it.activityInfo.packageName, it.activityInfo.name) }
    context.startActivity(intent)
}
