package com.armsx.flashpoint.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.armsx.flashpoint.R
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.safeDrawing
import kotlinx.coroutines.launch

/**
 * Menu sound effects: ARMSX2's MenuSfx (ARMSX2 team), ported. The bundled clips are ARMSX2's,
 * short interface blips trimmed from obsydianx's "Interface SFX Pack 1"
 * (https://obsydianx.itch.io/interface-sfx-pack-1), released CC0 and credited in About.
 *
 * A SoundPool for near-zero-latency one-shots with its own USAGE_GAME stream, so it never
 * competes with a game's audio. Each process (launcher, game) loads its own on first use.
 */
object MenuSfx {
    private const val EnabledKey = "menuSfx"
    private const val VolumeKey = "menuSfx.volume"
    // 15% as in ARMSX2: these fire on every interaction, so they sit well under everything else.
    private const val DefaultVolumePercent = 15

    enum class Event(val res: Int, val alt: Int = 0) {
        NAV(R.raw.sfx_nav_a, R.raw.sfx_nav_b), // alternates two soft ticks as the highlight moves
        SELECT(R.raw.sfx_click),
        MENU_OPEN(R.raw.sfx_menu),
        BACK(R.raw.sfx_back),
        TOGGLE_ON(R.raw.sfx_toggle_on),
        TOGGLE_OFF(R.raw.sfx_toggle_off),
        SLEEP(R.raw.sfx_sleep), // DS-lid-style chime as the device sleeps
        WAKE(R.raw.sfx_wake), // and as it wakes back into the app
        POPUP_OPEN(R.raw.sfx_popup_open),
        POPUP_CLOSE(R.raw.sfx_popup_close),
    }

    val enabled = mutableStateOf(true)
    val volumePercent = mutableStateOf(DefaultVolumePercent)

    private var prefs: android.content.SharedPreferences? = null
    private var pool: SoundPool? = null
    private val ids = HashMap<Event, Int>()
    private val altIds = HashMap<Event, Int>()
    private val lastPlay = HashMap<Event, Long>()
    private var navAlt = false

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean(EnabledKey, true)
        volumePercent.value = p.getInt(VolumeKey, DefaultVolumePercent)
        if (enabled.value) build(context.applicationContext)
    }

    fun set(context: Context, on: Boolean) {
        enabled.value = on
        prefs?.edit()?.putBoolean(EnabledKey, on)?.apply()
        if (on && pool == null) build(context.applicationContext) else if (!on) release()
    }

    fun play(event: Event) {
        if (!enabled.value) return
        val p = pool ?: return
        val now = SystemClock.uptimeMillis()
        // A fast D-pad walk ticks rather than buzzes.
        val gap = if (event == Event.NAV) 45L else 18L
        if (now - (lastPlay[event] ?: 0L) < gap) return
        val id = if (altIds.containsKey(event)) {
            navAlt = !navAlt
            (if (navAlt) altIds[event] else ids[event]) ?: return
        } else ids[event] ?: return
        lastPlay[event] = now
        val g = volumePercent.value.coerceIn(0, 100) / 100f
        runCatching { p.play(id, g, g, 1, 0, 1f) }
    }

    private fun build(context: Context) {
        release()
        val sp = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .build()
        Event.entries.forEach { ev ->
            runCatching {
                sp.load(context, ev.res, 1).let { if (it != 0) ids[ev] = it }
                if (ev.alt != 0) sp.load(context, ev.alt, 1).let { if (it != 0) altIds[ev] = it }
            }
        }
        pool = sp
    }

    private fun release() {
        ids.clear(); altIds.clear(); lastPlay.clear()
        pool?.let { runCatching { it.release() } }
        pool = null
    }
}

/**
 * Music for the in-game pause menu: ARMSX2's PauseMusic (ARMSX2 team), ported. Plays for as long
 * as the menu is up, easing in from silence and back out, and never behind a backgrounded app.
 * The track is "Underwater Ambient Pad" by isaiah658.
 */
object PauseMusic {
    private const val EnabledKey = "pauseMusic"
    private const val Volume = 0.45f
    private const val FadeInMs = 1600L
    private const val FadeOutMs = 500L
    private const val FadeSteps = 32

    val enabled = mutableStateOf(true)
    private var player: android.media.MediaPlayer? = null
    @Volatile private var foreground = true
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + kotlinx.coroutines.SupervisorJob())
    private var fade: kotlinx.coroutines.Job? = null
    private var prefs: android.content.SharedPreferences? = null

    fun load(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).also { enabled.value = it.getBoolean(EnabledKey, true) }
    }

    fun set(context: Context, on: Boolean, menuOpen: Boolean) {
        enabled.value = on
        prefs?.edit()?.putBoolean(EnabledKey, on)?.apply()
        if (!on) stop() else if (menuOpen) start(context)
    }

    /** From the game screen's onResume (true) and onPause (false). */
    fun setForeground(fg: Boolean, context: Context, menuOpen: Boolean) {
        foreground = fg
        if (!fg) pause() else if (menuOpen) start(context)
    }

    fun start(context: Context) {
        if (!enabled.value || !foreground) return
        player?.let { p ->
            fade?.cancel()
            runCatching { if (!p.isPlaying) p.start(); p.setVolume(Volume, Volume) }
            return
        }
        val ok = runCatching {
            android.media.MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(context, android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.pause_music}"))
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
                player = this
            }
        }.isSuccess
        if (ok) ramp(0f, Volume, FadeInMs) {}
    }

    /** Ease out, then stop: the menu closed. */
    fun stop() {
        val p = player ?: return
        ramp(Volume, 0f, FadeOutMs) {
            runCatching { p.stop(); p.release() }
            if (player === p) player = null
        }
    }

    private fun pause() {
        fade?.cancel()
        runCatching { player?.pause() }
    }

    private fun ramp(from: Float, to: Float, ms: Long, done: () -> Unit) {
        fade?.cancel()
        fade = scope.launch {
            for (i in 1..FadeSteps) {
                val v = from + (to - from) * i / FadeSteps
                runCatching { player?.setVolume(v, v) }
                kotlinx.coroutines.delay(ms / FadeSteps)
            }
            done()
        }
    }
}

/**
 * ARMSX2's sleep and wake (ARMSX2 team): a chime as the screen goes off with the app open, and
 * on the way back a wake chime and a brief "Welcome Back!" in the top left. A plain trip to the
 * home screen stays silent.
 */
object SleepWake {
    private var wasAsleep = false

    fun onPause(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm?.isInteractive == false) {
            wasAsleep = true
            MenuSfx.play(MenuSfx.Event.SLEEP)
        }
    }

    fun onResume() {
        if (!wasAsleep) return
        wasAsleep = false
        MenuSfx.play(MenuSfx.Event.WAKE)
        WelcomeBanner.show("Welcome Back!")
    }
}

/** ARMSX2's small transient banner in the top left (the "Welcome Back!" note on wake). */
object WelcomeBanner {
    val text = mutableStateOf<String?>(null)
    val token = androidx.compose.runtime.mutableIntStateOf(0)

    fun show(message: String) {
        text.value = message
        token.intValue += 1
    }
}

@androidx.compose.runtime.Composable
fun WelcomeBannerOverlay(scope: androidx.compose.foundation.layout.BoxScope) {
    val msg = WelcomeBanner.text.value ?: return
    androidx.compose.runtime.LaunchedEffect(WelcomeBanner.token.intValue) {
        kotlinx.coroutines.delay(2600)
        WelcomeBanner.text.value = null
    }
    with(scope) {
        androidx.compose.material3.Surface(
            modifier = androidx.compose.ui.Modifier
                .align(androidx.compose.ui.Alignment.TopStart)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .padding(start = 14.dp, top = 12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            color = androidx.compose.ui.graphics.Color(0xFF2A2A2D).copy(alpha = 0.92f),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF6CC63F).copy(alpha = 0.5f)),
            shadowElevation = 8.dp,
        ) {
            androidx.compose.material3.Text(
                text = msg,
                modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = androidx.compose.ui.graphics.Color(0xFFE6E6E8),
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
        }
    }
}
