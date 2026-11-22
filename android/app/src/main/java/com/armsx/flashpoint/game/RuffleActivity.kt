package com.armsx.flashpoint.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.armsx.flashpoint.core.FpCore
import java.io.File

/**
 * Native Flash: Ruffle in Rust (libfpruffle.so), drawing with Vulkan or OpenGL ES straight to
 * a surface. Runs in the `:game` process like the browser runner, with the same game server
 * (every file the movie loads comes from the data packs), the same touch overlay and the same
 * controller mapping. Adapted from ruffle-android's PlayerActivity (MIT OR Apache-2.0).
 */
class RuffleActivity : com.google.androidgamesdk.GameActivity() {
    private var proxyUrl = ""
    private var vkGipa = 0L
    private var driverName = ""
    private lateinit var overlay: OverlayState
    private lateinit var input: RuffleInput
    private lateinit var pad: PadMapper
    private lateinit var layouts: LayoutStore
    private var gameId = ""
    private var started = 0L
    private var restarting = false

    private val host = object : OverlayHost {
        override val title get() = intent.getStringExtra(GameActivity.EXTRA_TITLE).orEmpty()
        override val logoUrl get() = intent.getStringExtra(GameActivity.EXTRA_LOGO).orEmpty()
        override val gameId get() = intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()
        override val usesDriver = true
        override val isFlash = true
        override val runner: String
            get() = "Flash: native Ruffle (" + when (getString("backend")) { "vulkan" -> "Vulkan"; "gl" -> "OpenGL ES"; else -> "Auto" } +
                (if (driverName.isNotEmpty()) ", $driverName" else "") + ")"
        override fun save(asDefault: Boolean) = layouts.save(gameId, overlay.layout, asDefault)
        override fun keyboard() {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            mSurfaceView.requestFocus()
            imm.showSoftInput(mSurfaceView, 0)
        }
        override fun restart() {
            // Same process, fresh player: start the game again, then let this one go.
            restarting = true
            val id = intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()
            startActivity(Intent(intent).setClass(this@RuffleActivity, FlashPrefs.activity(this@RuffleActivity, id))
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_FORWARD_RESULT))
            finish()
        }
        override fun exit() = quit()
        override fun frames(): Long = nativeFrames()
        override val hasVolume get() = true
        override fun setVolume(v: Float) = nativeVolume(v)
        override fun menuOpen(open: Boolean) {
            screen?.onMenu(open)
            nativeSetPlaying(!open)
        }
        override fun display(scale: String, quality: String) = nativeDisplay(scaleCode(scale), qualityCode(quality))
        override var menuLeft: Boolean
            get() = layouts.menuLeft
            set(v) { layouts.menuLeft = v }
    }

    private fun scaleCode(s: String) = when (s) { "fit" -> 1; "stretch" -> 2; "fill" -> 3; "original" -> 4; else -> 0 }
    private fun qualityCode(q: String) = when (q) { "low" -> 0; "medium" -> 1; "best" -> 3; else -> 2 }

    // Read and written by Rust: the player loop's event sender.
    @Suppress("unused")
    private val eventLoopHandle: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // The game server must be up before super.onCreate starts the native player loop.
        try {
            val port = FpCore.startServer(
                htdocs = intent.getStringExtra(GameActivity.EXTRA_HTDOCS).orEmpty(),
                legacyBase = intent.getStringExtra(GameActivity.EXTRA_LEGACY).orEmpty(),
                cacheRemote = true,
                liveDomains = if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("liveWeb", true))
                    listOfNotNull(Launch.siteOf(intent.getStringExtra(GameActivity.EXTRA_URL).orEmpty())) else emptyList(),
            )
            intent.getStringArrayExtra(GameActivity.EXTRA_ZIPS).orEmpty().forEach { FpCore.mount(it) }
            proxyUrl = "http://127.0.0.1:$port"
        } catch (e: Exception) {
            Toast.makeText(this, "Game server failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
        // A custom Vulkan driver, if one is chosen and Flash may use Vulkan.
        if (FlashPrefs.backend(this, intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()) != "gl") {
            com.armsx.flashpoint.data.CustomDriver.selected(this, intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty())?.let { d ->
                vkGipa = com.armsx.flashpoint.data.CustomDriver.open(this, d)
                driverName = if (vkGipa != 0L) d.name else ""
                if (vkGipa == 0L) Toast.makeText(this, com.armsx.flashpoint.data.FpDriver.lastError(), Toast.LENGTH_LONG).show()
            }
        }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        gameId = intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()
        layouts = LayoutStore(this)
        overlay = OverlayState(layouts.load(gameId))
        input = RuffleInput(this)
        pad = PadMapper(input, overlay, { mSurfaceView.width }) { e -> super@RuffleActivity.dispatchKeyEvent(e) }
        screen = GameScreen(this, overlay) { if (::pad.isInitialized) pad else null }.also { it.onCreate() }
        val st = overlay
        addContentView(
            ComposeView(this).apply {
                setContent {
                    com.armsx.flashpoint.ui.FpTheme {
                        GameOverlay(st, input, host)
                    }
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        mSurfaceView.post { input.moveCursor(mSurfaceView.width / 2f, mSurfaceView.height / 2f) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                overlay.editing = false
                overlay.menu = !overlay.menu
            }
        })
        started = SystemClock.elapsedRealtime()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        (::pad.isInitialized && pad.key(event)) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        (::pad.isInitialized && pad.motion(ev)) || super.dispatchGenericMotionEvent(ev)

    private var screen: GameScreen? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        screen?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        screen?.onResume()
    }

    override fun onPause() {
        screen?.onPause()
        super.onPause()
    }

    private fun quit() {
        if (::input.isInitialized) input.releaseAll()
        val secs = (SystemClock.elapsedRealtime() - started) / 1000
        setResult(Activity.RESULT_OK, Intent().putExtra(GameActivity.EXTRA_GAME_ID, gameId).putExtra(GameActivity.EXTRA_SECONDS, secs))
        finish()
    }

    override fun onDestroy() {
        screen?.onDestroy()
        super.onDestroy()
        if (isFinishing && !restarting) android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---- read by Rust ------------------------------------------------------------------

    private val loc = IntArray(2)

    @Suppress("unused")
    private val locInWindow: IntArray
        get() {
            mSurfaceView.getLocationInWindow(loc)
            return loc
        }

    @Suppress("unused")
    private val surfaceWidth: Int get() = mSurfaceView.width

    @Suppress("unused")
    private val surfaceHeight: Int get() = mSurfaceView.height

    @Suppress("unused")
    private fun getString(key: String): String? = when (key) {
        "swfUrl" -> intent.getStringExtra(GameActivity.EXTRA_URL)
        "proxyUrl" -> proxyUrl
        // vkGetInstanceProcAddr of a custom driver (decimal address), or "0".
        "vkGipa" -> vkGipa.toString()
        "sdk" -> android.os.Build.VERSION.SDK_INT.toString()
        "backend" -> FlashPrefs.backend(this, intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty())
        // Flash cookies (SharedObjects), kept per install like the desktop's.
        "storageDir" -> File(filesDir, "ruffle/shared_objects").apply { mkdirs() }.path
        "scale" -> scaleCode(LayoutStore(this).load(intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()).scale).toString()
        "quality" -> qualityCode(LayoutStore(this).load(intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()).quality).toString()
        else -> null
    }

    // ---- into Rust ---------------------------------------------------------------------

    external fun nativeKey(code: Int, down: Boolean)
    external fun nativeMouse(x: Double, y: Double, button: Int, down: Boolean)
    external fun nativeSetPlaying(playing: Boolean)
    external fun nativeFrames(): Long
    external fun nativeVolume(volume: Float)
    external fun nativeDisplay(scale: Int, quality: Int)

    companion object {
        init {
            System.loadLibrary("fpruffle")
            nativeInit()
        }

        @JvmStatic private external fun nativeInit()

        fun intent(ctx: Context, base: Intent): Intent = Intent(base).setClass(ctx, RuffleActivity::class.java)
    }
}

/** GameInput for native Ruffle: straight into the player over JNI. */
class RuffleInput(private val act: RuffleActivity) : GameInput {
    private val held = HashSet<Int>()
    private var buttons = 0
    override var cursorX = 0f
        private set
    override var cursorY = 0f
        private set

    override fun key(code: Int, down: Boolean) {
        if (down && !held.add(code)) return
        if (!down && !held.remove(code)) return
        act.nativeKey(code, down)
    }

    override fun moveCursor(x: Float, y: Float) {
        cursorX = x.coerceAtLeast(0f)
        cursorY = y.coerceAtLeast(0f)
        act.nativeMouse(cursorX.toDouble(), cursorY.toDouble(), 0, false)
    }

    private fun which(button: Int) = if (button == MotionEvent.BUTTON_SECONDARY) 2 else 1

    override fun mouseDown(button: Int) {
        if (buttons and button != 0) return
        buttons = buttons or button
        act.nativeMouse(cursorX.toDouble(), cursorY.toDouble(), which(button), true)
    }

    override fun mouseUp(button: Int) {
        if (buttons and button == 0) return
        buttons = buttons and button.inv()
        act.nativeMouse(cursorX.toDouble(), cursorY.toDouble(), which(button), false)
    }

    override fun releaseAll() {
        held.toList().forEach { key(it, false) }
        if (buttons and MotionEvent.BUTTON_PRIMARY != 0) mouseUp(MotionEvent.BUTTON_PRIMARY)
        if (buttons and MotionEvent.BUTTON_SECONDARY != 0) mouseUp(MotionEvent.BUTTON_SECONDARY)
    }
}
