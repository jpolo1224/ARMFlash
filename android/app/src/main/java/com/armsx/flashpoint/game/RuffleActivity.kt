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
            // The menu gets the screen's full rate at once, and the game its own back on closing.
            menuUp = open
            ticker.removeCallbacks(frameRateTick)
            frameRateTick.run()
        }
        override fun display(scale: String, quality: String) = nativeDisplay(scaleCode(scale), qualityCode(quality))
        override val hasShaders get() = true
        override fun flashTrouble(): Long = nativeTrouble()
        override fun shaderChanged(full: Boolean) = nativeShaderChanged(full)
        override val hasFrameGen get() = true
        override fun frameGenChanged() = nativeFrameGenChanged()
        override fun frameGenStatus(): String = nativeFrameGenStatus()
        override fun frameGenReason(): Int = nativeFrameGenReason()
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
        com.armsx.flashpoint.core.Php.ensure(this)
        try {
            val port = FpCore.startServer(
                htdocs = intent.getStringExtra(GameActivity.EXTRA_HTDOCS).orEmpty(),
                legacyBase = intent.getStringExtra(GameActivity.EXTRA_LEGACY).orEmpty(),
                cacheRemote = true,
                liveDomains = if (com.armsx.flashpoint.data.Library.liveWeb(getSharedPreferences("settings", MODE_PRIVATE)))
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
        frameRateTick.run()
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
        // A webcam game gets its camera back.
        if (cameraWanted != null && checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) openCamera()
    }

    override fun onPause() {
        screen?.onPause()
        closeCamera()
        super.onPause()
    }

    private fun quit() {
        if (::input.isInitialized) input.releaseAll()
        val secs = (SystemClock.elapsedRealtime() - started) / 1000
        setResult(Activity.RESULT_OK, Intent().putExtra(GameActivity.EXTRA_GAME_ID, gameId).putExtra(GameActivity.EXTRA_SECONDS, secs))
        finish()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(frameRateTick)
        closeCamera()
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
        // A RetroArch shader preset for this game (absolute path, "" for none) and its changed
        // parameters, one "name value" per line.
        "shaderPreset" -> com.armsx.flashpoint.shaders.ShaderPrefs.presetFile(this, intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty())?.path.orEmpty()
        "shaderParams" -> com.armsx.flashpoint.shaders.ShaderPrefs.presetFile(this, intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty())
            ?.let { com.armsx.flashpoint.shaders.ShaderPrefs.paramLines(this, it.path) }.orEmpty()
        "storageDir" -> File(filesDir, "ruffle/shared_objects").apply { mkdirs() }.path
        // Frame generation's settings and the screen's refresh rate (FrameGenPrefs.native).
        "frameGen" -> FrameGenPrefs.native(this, displayHz())
        "scale" -> scaleCode(LayoutStore(this).load(intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()).scale).toString()
        "quality" -> qualityCode(LayoutStore(this).load(intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()).quality).toString()
        // The cameras webcam games can use, one name per line, front first.
        "cameras" -> cameras.joinToString("\n") { it.second }
        else -> null
    }

    // ---- into Rust ---------------------------------------------------------------------

    external fun nativeKey(code: Int, down: Boolean)
    external fun nativeMouse(x: Double, y: Double, button: Int, down: Boolean)
    external fun nativeSetPlaying(playing: Boolean)
    external fun nativeFrames(): Long
    external fun nativeVolume(volume: Float)
    external fun nativeDisplay(scale: Int, quality: Int)
    /** The shader settings changed: reload the preset ([full]) or only its parameters. */
    external fun nativeShaderChanged(full: Boolean)
    /** Errors Ruffle has reported (script errors, failed loads, panics). */
    external fun nativeTrouble(): Long
    /** Frame generation's settings changed (it reads them again through getString("frameGen")). */
    external fun nativeFrameGenChanged()
    /** Why frame generation cannot run (FrameGenPrefs.Reason), 0 when it can. */
    external fun nativeFrameGenReason(): Int
    /** Frame generation's overlay line, "" while it is off. */
    external fun nativeFrameGenStatus(): String
    /** The screen's refresh rate now; returns the rate frame generation would like it at (0: none). */
    external fun nativeFrameGenTick(displayHz: Float): Float

    // ---- the screen's refresh rate, for frame generation ---------------------------------

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var votedHz = 0f

    /**
     * The quick menu or the touch editor is up. The game's vote below holds the whole app to the
     * game's rate (Android applies it to every window of the app), so a 20 fps game with frame
     * generation drew the menu at 40 fps; the menu gets the screen's fastest rate instead.
     */
    private var menuUp = false

    @Suppress("DEPRECATION")
    private fun fastestHz(): Float = runCatching {
        (if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay)?.supportedModes?.maxOfOrNull { it.refreshRate }
    }.getOrNull() ?: 0f

    @Suppress("DEPRECATION")
    private fun displayHz(): Float = runCatching {
        (if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay)?.refreshRate
    }.getOrNull()?.takeIf { it > 1f } ?: 60f

    /**
     * Every two seconds: tell frame generation the screen's rate (it makes no more frames than the
     * screen can show), and ask Android for the rate it would like, the game's rate times the
     * multiplier. Without that vote the Odin 3 keeps a game at 60 Hz on its 120 Hz panel
     * (game_default_frame_rate_override). Seamless changes only, so the screen never blanks.
     */
    private val frameRateTick = object : Runnable {
        override fun run() {
            val game = runCatching { nativeFrameGenTick(displayHz()) }.getOrDefault(0f)
            val want = if (menuUp) fastestHz() else game
            if (android.os.Build.VERSION.SDK_INT >= 30 && want != votedHz) {
                val surface = mSurfaceView.holder.surface
                if (surface != null && surface.isValid) {
                    runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            surface.setFrameRate(want, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                                android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
                        } else {
                            surface.setFrameRate(want, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                        }
                        votedHz = want
                    }
                }
            }
            ticker.postDelayed(this, 2000)
        }
    }
    /** A camera frame's Y, U and V planes (YUV_420_888) and how far it turns to stand up. */
    external fun nativeCameraFrame(y: java.nio.ByteBuffer, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer, width: Int, height: Int, yStride: Int, uvStride: Int, uvStep: Int, rotation: Int)
    /** The camera permission: 1 allowed, 0 asking, -1 refused. */
    external fun nativeCameraPermission(state: Int)

    // ---- the camera, for webcam games ----------------------------------------------------

    /** The cameras (id and the name Flash shows), front first. */
    private val cameras: List<Pair<String, String>> by lazy {
        runCatching {
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val facing = android.hardware.camera2.CameraCharacteristics.LENS_FACING
            val byFacing = cm.cameraIdList.map { id -> id to cm.getCameraCharacteristics(id).get(facing) }
                .sortedBy { when (it.second) { android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT -> 0; android.hardware.camera2.CameraMetadata.LENS_FACING_BACK -> 1; else -> 2 } }
            val seen = HashMap<String, Int>()
            byFacing.map { (id, f) ->
                val base = when (f) {
                    android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT -> "Front Camera"
                    android.hardware.camera2.CameraMetadata.LENS_FACING_BACK -> "Back Camera"
                    else -> "Camera"
                }
                val n = (seen[base] ?: 0) + 1
                seen[base] = n
                id to (if (n > 1) "$base $n" else base)
            }
        }.getOrDefault(emptyList())
    }

    /** What the game asked for: camera index, width, height, frames a second. */
    private var cameraWanted: IntArray? = null
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var cameraSession: android.hardware.camera2.CameraCaptureSession? = null
    private var cameraReader: android.media.ImageReader? = null
    private var cameraThread: android.os.HandlerThread? = null

    /** From Rust (camera.rs), when a webcam game asks for the camera or changes its mode. */
    @Suppress("unused")
    private fun startCamera(index: Int, width: Int, height: Int, fps: Int) {
        runOnUiThread {
            cameraWanted = intArrayOf(index, width, height, fps)
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                nativeCameraPermission(1)
                openCamera()
            } else {
                // Flash asked before a movie could use the camera; Android asks here.
                nativeCameraPermission(0)
                overlay.holdMenu = true
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_REQUEST)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        overlay.holdMenu = false
        val ok = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        nativeCameraPermission(if (ok) 1 else -1)
        if (ok) openCamera()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun openCamera() {
        closeCamera()
        val want = cameraWanted ?: return
        val (id, _) = cameras.getOrNull(want[0]) ?: cameras.firstOrNull() ?: return
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
        val chars = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: return
        val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        // The camera's own size nearest the game's mode, as Flash picks the closest native mode
        // (kept small: games read every pixel).
        val size = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            ?.filter { it.width <= 1280 && it.height <= 960 }
            ?.minByOrNull { kotlin.math.abs(it.width - want[1]) + kotlin.math.abs(it.height - want[2]) } ?: return
        val sensor = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val front = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
        val thread = android.os.HandlerThread("armflash-camera").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val reader = android.media.ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val p = img.planes
                nativeCameraFrame(p[0].buffer, p[1].buffer, p[2].buffer, img.width, img.height, p[0].rowStride, p[1].rowStride, p[1].pixelStride, frameRotation(sensor, front))
            } finally {
                img.close()
            }
        }, handler)
        cameraThread = thread
        cameraReader = reader
        runCatching {
            cm.openCamera(id, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(device: android.hardware.camera2.CameraDevice) {
                    if (cameraReader !== reader) { device.close(); return }
                    cameraDevice = device
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(reader.surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            cameraSession = session
                            val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader.surface)
                                chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                    ?.minByOrNull { kotlin.math.abs(it.upper - want[3]) * 4 + (it.upper - it.lower) }
                                    ?.let { set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            }.build()
                            runCatching { session.setRepeatingRequest(request, null, handler) }
                                .onFailure { android.util.Log.w("ARMFlash", "camera: ${it.message}") }
                        }

                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                            android.util.Log.w("ARMFlash", "camera: the capture session could not be set up")
                        }
                    }, handler)
                }

                override fun onDisconnected(device: android.hardware.camera2.CameraDevice) {
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                }

                override fun onError(device: android.hardware.camera2.CameraDevice, error: Int) {
                    android.util.Log.w("ARMFlash", "camera: error $error")
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                }
            }, handler)
        }.onFailure { android.util.Log.w("ARMFlash", "camera: ${it.message}") }
    }

    private fun closeCamera() {
        runCatching { cameraSession?.close() }
        cameraSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { cameraReader?.close() }
        cameraReader = null
        cameraThread?.quitSafely()
        cameraThread = null
    }

    /** How far a frame turns clockwise to stand up on the screen as the device is held now. */
    private fun frameRotation(sensor: Int, front: Boolean): Int {
        @Suppress("DEPRECATION")
        val display = when (windowManager.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (front) (sensor + display) % 360 else (sensor - display + 360) % 360
    }

    companion object {
        init {
            System.loadLibrary("fpruffle")
            nativeInit()
        }

        @JvmStatic private external fun nativeInit()
        private const val CAMERA_REQUEST = 4242

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
