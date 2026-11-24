package com.armsx.flashpoint.win

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Windows support (the runtime) and its 3D file, downloaded once from ARMFlash's GitHub release:
 * with the game database in first-time setup, from Config, or when a Windows game finds them
 * missing. One download for the whole app, so every place shows the same progress and a second
 * request joins the one already running.
 *
 * First touched on the main thread (setup, Config, a game's launch), which its Compose state needs.
 */
object WinSetup {
    enum class Part(val label: String, val url: String, val sha256: String) {
        RUNTIME("Windows players", WinRuntime.RUNTIME_URL, WinRuntime.RUNTIME_SHA256),
        GRAPHICS("3D for Windows players", WinRuntime.GRAPHICS_URL, WinRuntime.GRAPHICS_SHA256),
    }

    /** What is downloading now ("Windows players: Downloading… 12 of 78 MB"), or null. */
    var progress by mutableStateOf<String?>(null)
        private set
    /** Why the last download stopped, until the next one starts. */
    var failed by mutableStateOf<String?>(null)
        private set
    /** Bumped when a part finishes installing, so screens showing what is installed look again. */
    var installs by mutableStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    fun missing(ctx: Context): List<Part> {
        val rt = WinRuntime(ctx)
        return listOfNotNull(Part.RUNTIME.takeIf { !rt.installed }, Part.GRAPHICS.takeIf { !rt.has3d })
    }

    /** Download what is missing, or join the download already running. */
    fun start(ctx: Context): Job {
        job?.takeIf { it.isActive }?.let { return it }
        val app = ctx.applicationContext
        failed = null
        return scope.launch {
            val rt = WinRuntime(app)
            for (part in missing(app)) {
                progress = "${part.label}: starting…"
                var last = ""
                val ok = runCatching { rt.download(part.url, part.sha256) { last = it; progress = "${part.label}: $it" } }
                    .getOrElse { last = it.message ?: "error"; false }
                if (!ok) {
                    failed = "${part.label}: ${last.ifEmpty { "download failed" }}"
                    Log.w(WinRuntime.TAG, "setup: $failed")
                    break
                }
                Log.i(WinRuntime.TAG, "setup: ${part.label} installed")
                installs++
            }
            progress = null
        }.also { job = it }
    }
}
