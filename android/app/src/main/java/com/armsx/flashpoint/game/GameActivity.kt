package com.armsx.flashpoint.game

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.armsx.flashpoint.core.FpCore
import java.util.concurrent.Executors

/**
 * Runs one game in its own process (`:game`): its own game server, its own WebView, and
 * when it ends the process goes away with everything it loaded.
 *
 * Every request the WebView makes goes through the in-process game server, so archived
 * pages load from their original URLs (http://www.example.com/...) straight out of the
 * data-pack zips. Paths under `/__fpm/` are app assets (the Ruffle player) on whatever
 * host the game uses, so sitelocked Flash still sees its own domain.
 */
class GameActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var started = 0L
    private lateinit var inj: Injector
    private lateinit var overlay: OverlayState
    private lateinit var layouts: LayoutStore
    private var gameId = ""
    private var displayScript: androidx.webkit.ScriptHandler? = null
    @Volatile private var pageFrames = -1L

    private val host = object : OverlayHost {
        override val title get() = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        override val logoUrl get() = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        override val gameId get() = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        override val usesDriver = false
        override val isFlash get() = intent.getStringExtra(EXTRA_RUNNER) == Runner.FLASH.name || isFlashPage
        override val isFlashPage get() = intent.hasExtra(com.armsx.flashpoint.win.WinActivity.EXTRA_APP)
        override fun flashTrouble(): Long = consoleErrors
        override val runner get() = if (intent.getStringExtra(EXTRA_RUNNER) == Runner.FLASH.name) "Flash: Ruffle in the browser" else "Browser"
        override fun save(asDefault: Boolean) = layouts.save(gameId, overlay.layout, asDefault)
        override fun keyboard() = showKeyboard()
        override fun restart() {
            // A Flash game switched to the native player starts over there.
            val player = FlashPrefs.player(this@GameActivity, gameId, isFlashPage)
            if (isFlash && player == "flashplayer") {
                // Flash Player under Wine: the standalone player for a SWF, the game's own browser
                // with the plugin for a page (its program came along in the intent).
                restarting = true
                startActivity(Intent(intent).setClass(this@GameActivity, com.armsx.flashpoint.win.WinActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_FORWARD_RESULT))
                finish()
            } else if (isFlash && !isFlashPage && player == "native") {
                restarting = true
                startActivity(Intent(intent).setClass(this@GameActivity, RuffleActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_FORWARD_RESULT))
                finish()
            } else web.reload()
        }
        override fun exit() = quit()
        // The page's frame count (a requestAnimationFrame counter in the top frame), read
        // asynchronously: each call returns the last value and asks for a fresh one.
        override fun frames(): Long {
            web.evaluateJavascript("window.__fpmFrames||-1") { v -> pageFrames = v?.toLongOrNull() ?: -1 }
            return pageFrames
        }
        override fun menuOpen(open: Boolean) {
            screen?.onMenu(open)
            // Audio first, while the page still runs: pausing the WebView does not stop Web Audio.
            if (open) {
                web.evaluateJavascript("window.__fpmPause && window.__fpmPause(true)") { web.onPause(); web.pauseTimers() }
            } else {
                web.resumeTimers(); web.onResume(); web.requestFocus()
                web.evaluateJavascript("window.__fpmPause && window.__fpmPause(false)", null)
            }
        }
        override fun display(scale: String, quality: String) {
            setDisplayScript(scale, quality)
            web.reload()
        }
        override var menuLeft: Boolean
            get() = layouts.menuLeft
            set(v) { layouts.menuLeft = v }
    }

    /** Ruffle (web) reads its scale and quality from this, set before every page load. */
    private fun setDisplayScript(scale: String, quality: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        displayScript?.remove()
        displayScript = WebViewCompat.addDocumentStartJavaScript(web, RufflePage.displayJs(scale, quality), setOf("*"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val runner = Runner.valueOf(intent.getStringExtra(EXTRA_RUNNER) ?: Runner.WEB.name)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val zips = intent.getStringArrayExtra(EXTRA_ZIPS).orEmpty()

        // PHP pages (unpacked by the launcher before the game started; here it is only pointed to).
        com.armsx.flashpoint.core.Php.ensure(this)
        val port = try {
            val p = FpCore.startServer(
                htdocs = intent.getStringExtra(EXTRA_HTDOCS).orEmpty(),
                legacyBase = intent.getStringExtra(EXTRA_LEGACY).orEmpty(),
                cacheRemote = true,
                liveDomains = if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("liveWeb", true))
                    listOfNotNull(Launch.siteOf(intent.getStringExtra(GameActivity.EXTRA_URL).orEmpty())) +
                        LiveSites.firstFor(intent.getStringExtra(EXTRA_GAME_ID).orEmpty())
                else emptyList(),
            )
            zips.forEach { FpCore.mount(it) }
            p
        } catch (e: Exception) {
            Toast.makeText(this, "Game server failed: ${e.message}", Toast.LENGTH_LONG).show()
            return finish()
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
                // Look like Chrome for Android, not a WebView: old game pages read "Version/4.0"
                // and "; wv" as Android's stock browser and refuse to start ("Please launch
                // Google Chrome", Ninja Painter 2 and other Spil/Silvergames titles).
                userAgentString = userAgentString.replace("; wv", "").replace(Regex("""Version/\d+(\.\d+)* """), "")
            }
            webViewClient = Client(this@GameActivity).apply { site = Launch.siteOf(url) }
            // Links a game opens in a new tab or window (sponsor and "more games" links,
            // window.open) are dropped, like a blocked pop-up: without this the WebView loads
            // them in place of the game.
            settings.setSupportMultipleWindows(true)
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    // Ruffle (web) reports script errors and failed loads as console errors.
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR && "ruffle" in m.sourceId().lowercase()) consoleErrors++
                    android.util.Log.i("fpgame", "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                    return true
                }
            }
        }
        gameId = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        layouts = LayoutStore(this)
        inj = Injector(web)
        overlay = OverlayState(layouts.load(gameId))
        val ui = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                val st = this@GameActivity.overlay
                com.armsx.flashpoint.ui.FpTheme { GameOverlay(st, inj, host) }
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(web)
            addView(ui)
        })
        pad = PadMapper(inj, overlay, { web.width }) { e -> super@GameActivity.dispatchKeyEvent(e) }
        screen = GameScreen(this, overlay) { if (::pad.isInitialized) pad else null }.also { it.onCreate() }
        setDisplayScript(overlay.layout.scale, overlay.layout.quality)
        web.post { inj.moveCursor(web.width / 2f, web.height / 2f) }

        // Ruffle polyfills <embed>/<object> Flash on any page the WebView loads.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, RufflePage.POLYFILL, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(web, RufflePage.EMBED_FRAMES, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(web, RufflePage.AUDIO_GATE, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(web, RufflePage.PAGE_FIT, setOf("*"))
        }

        val target = when (runner) {
            Runner.FLASH -> RufflePage.playerUrl(url)
            else -> url
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back opens the in-game menu; Exit is in there. A second Back closes it again.
            override fun handleOnBackPressed() {
                overlay.editing = false
                overlay.menu = !overlay.menu
            }
        })

        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // No direct fallback: everything, http and https, goes through the game server.
            val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
            ProxyController.getInstance().setProxyOverride(cfg, Executors.newSingleThreadExecutor()) {
                runOnUiThread { web.loadUrl(target) }
            }
        } else {
            Toast.makeText(this, "This WebView is too old to route games (needs proxy override).", Toast.LENGTH_LONG).show()
            finish()
        }
        started = SystemClock.elapsedRealtime()
    }

    private fun showKeyboard() {
        web.requestFocus()
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
    }

    // Controllers: the shared mapping (PadMapper), Back opens the menu.
    private lateinit var pad: PadMapper

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        (::pad.isInitialized && pad.key(event)) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        (::pad.isInitialized && pad.motion(ev)) || super.dispatchGenericMotionEvent(ev)

    private var screen: GameScreen? = null

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        screen?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun quit() {
        if (::inj.isInitialized) inj.releaseAll()
        val secs = (SystemClock.elapsedRealtime() - started) / 1000
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_GAME_ID, intent.getStringExtra(EXTRA_GAME_ID)).putExtra(EXTRA_SECONDS, secs))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized && !overlay.menu) web.onResume()
        screen?.onResume()
    }

    override fun onPause() {
        screen?.onPause()
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        screen?.onDestroy()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
        // The game process exists for one game; take everything it loaded with it.
        if (isFinishing && !restarting) android.os.Process.killProcess(android.os.Process.myPid())
    }

    private var restarting = false
    @Volatile private var consoleErrors = 0L

    private class Client(private val ctx: Context) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            RufflePage.intercept(ctx, request.url)

        /**
         * Every request from this WebView goes to the local game server (proxy override), which
         * ends https:// itself with a certificate it made for that host. Those are the only
         * certificates this WebView ever sees, so they are accepted.
         */
        @android.annotation.SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
            handler.proceed()
        }

        /** The game's own site; the page may move around inside it (menus, next level pages). */
        var site: String? = null

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.scheme !in setOf("http", "https")) return true
            // Games that try to leave for another site (sponsor links, "more games", a logo
            // linking home) stay put: those sites were never archived and would only show a
            // "not found" page in place of the game.
            val to = Launch.siteOf(request.url.toString())
            return site != null && to != site
        }
    }

    companion object {
        const val EXTRA_RUNNER = "runner"
        const val EXTRA_URL = "url"
        const val EXTRA_ZIPS = "zips"
        const val EXTRA_HTDOCS = "htdocs"
        const val EXTRA_LEGACY = "legacy"
        const val EXTRA_GAME_ID = "gameId"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOGO = "logo"

        fun intent(ctx: Context, gameId: String, runner: Runner, url: String, zips: List<String>, htdocs: String, legacy: String, title: String = "", logo: String = "") =
            Intent(ctx, GameActivity::class.java)
                .putExtra(EXTRA_GAME_ID, gameId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_LOGO, logo)
                .putExtra(EXTRA_RUNNER, runner.name)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_ZIPS, zips.toTypedArray())
                .putExtra(EXTRA_HTDOCS, htdocs)
                .putExtra(EXTRA_LEGACY, legacy)
    }
}

/** The Ruffle player page and its assets, served under `/__fpm/` on the game's own host. */
object RufflePage {
    private const val PREFIX = "/__fpm/"

    const val CONFIG = """{"autoplay":"on","unmuteOverlay":"hidden","splashScreen":false,"letterbox":"on","contextMenu":"rightClickOnly","openUrlMode":"deny","allowNetworking":"all","publicPath":"/__fpm/ruffle/","polyfills":true,"maxExecutionDuration":30}"""

    val POLYFILL = """
        (function(){
          if (window.__fpmRuffle) return; window.__fpmRuffle = true;
          window.RufflePlayer = window.RufflePlayer || {};
          window.RufflePlayer.config = Object.assign($CONFIG, window.__fpmDisplay || {});
          var s = document.createElement('script'); s.src = '/__fpm/ruffle/ruffle.js';
          (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    /**
     * Pages that show a web page through `<embed src="index.html">` (or `<object data=...>`).
     * The desktop launcher's browser draws those like an iframe; Android's WebView, which has no
     * plugins, draws nothing, so the game sat on a black page (Phineas and Ferb: Drusselstein
     * Driving Test, and about 60 other HTML5 entries). Each such element becomes an iframe with
     * the same size and styling. Flash embeds are left for Ruffle.
     */
    val EMBED_FRAMES = """
        (function(){
          if (window.__fpmFrames) return; window.__fpmFrames = true;
          var page = /\.(x?html?|php)([?#].*)?$/i;
          function fix(el) {
            if (el.__fpmDone) return; el.__fpmDone = true;
            var src = el.getAttribute(el.tagName === 'OBJECT' ? 'data' : 'src') || '';
            var type = (el.getAttribute('type') || '').toLowerCase();
            if (!(type.indexOf('html') >= 0 || (!type && page.test(src)))) return;
            var f = document.createElement('iframe');
            f.src = src;
            ['width', 'height', 'style', 'class', 'id', 'name'].forEach(function(a) {
              var v = el.getAttribute(a); if (v != null) f.setAttribute(a, v);
            });
            f.setAttribute('frameborder', '0');
            f.setAttribute('allow', 'autoplay; fullscreen; gamepad');
            if (!f.style.border) f.style.border = '0';
            el.parentNode.replaceChild(f, el);
          }
          function scan(root) {
            (root.querySelectorAll ? root.querySelectorAll('embed, object') : []).forEach(fix);
          }
          new MutationObserver(function(ms) {
            ms.forEach(function(m) { m.addedNodes.forEach(function(n) {
              if (n.nodeType !== 1) return;
              if (n.tagName === 'EMBED' || n.tagName === 'OBJECT') fix(n); else scan(n);
            }); });
          }).observe(document.documentElement || document, { childList: true, subtree: true });
          document.addEventListener('DOMContentLoaded', function() { scan(document); });
        })();
    """.trimIndent()

    /**
     * Silence for the pause menu. Pausing the WebView stops timers and drawing but not Web Audio
     * or <audio>/<video>, so a paused game kept playing its music. Every AudioContext a page
     * makes is remembered; `__fpmPause(true)` suspends them all, pauses playing media and Ruffle
     * players, and passes the call into every frame (games inside iframes, other origins too).
     */
    val AUDIO_GATE = """
        (function(){
          if (window.__fpmPause) return;
          var ctxs = [], paused = [];
          ['AudioContext', 'webkitAudioContext'].forEach(function(n) {
            var C = window[n]; if (!C) return;
            var W = function() { var c = new (Function.prototype.bind.apply(C, [null].concat([].slice.call(arguments))))(); ctxs.push(c); return c; };
            W.prototype = C.prototype; window[n] = W;
          });
          function frames(on) {
            for (var i = 0; i < window.frames.length; i++) {
              try { window.frames[i].postMessage({ __fpmPause: on }, '*'); } catch (e) {}
            }
          }
          window.__fpmPause = function(on) {
            ctxs.forEach(function(c) { try { on ? c.suspend() : c.resume(); } catch (e) {} });
            if (on) {
              paused = [].filter.call(document.querySelectorAll('audio, video'), function(m) { return !m.paused; });
              paused.forEach(function(m) { m.pause(); });
            } else {
              paused.forEach(function(m) { m.play().catch(function(){}); }); paused = [];
            }
            [].forEach.call(document.querySelectorAll('ruffle-player, ruffle-embed, ruffle-object'), function(p) {
              try { on ? p.pause() : p.play(); } catch (e) {}
            });
            frames(on);
          };
          if (window.top === window) {
            window.__fpmFrames = 0;
            (function tick() { window.__fpmFrames++; requestAnimationFrame(tick); })();
          }
          window.addEventListener('message', function(e) {
            if (e.data && typeof e.data.__fpmPause === 'boolean') window.__fpmPause(e.data.__fpmPause);
          });
        })();
    """.trimIndent()

    /**
     * Browser games as the game, not the web page around it. The game element (the largest
     * canvas, video, frame or plugin) is pinned over the page; the page itself is never scaled,
     * so engines that size themselves from the page around them (Phaser, Construct, ...) and
     * pages that zoom do not fight it:
     *  - a frame or a plugin (Happy Wheels HTML5's iframe, Flash through Ruffle) gets the whole
     *    screen and lays out or scales its own content, as in a browser's full screen;
     *  - a canvas or video (with a wrapper its own size, overlays included) is scaled to the
     *    screen with a transform on it alone, which pointer positions follow.
     * A game that already fills the window is left alone. The Scale setting picks the fit: keep
     * the shape (Game default, Fit), Stretch, Fill, or the page as its site laid it out
     * (Original size). Kept up as the game resizes or appears.
     */
    val PAGE_FIT = """
        (function(){
          if (window.top !== window || window.__fpmFit) return;
          window.__fpmFit = true;
          function mode() { return (window.__fpmDisplay && window.__fpmDisplay.pageFit) || 'fit'; }
          var PROPS = ['position', 'left', 'top', 'right', 'bottom', 'width', 'height', 'max-width', 'max-height',
            'margin', 'border', 'transform', 'transform-origin', 'z-index'];
          var pinned = null, saved = null, key = '';
          function set(e, v) {
            for (var k in v) e.style.setProperty(k, v[k], 'important');
          }
          function unpin() {
            if (!pinned) return;
            for (var i = 0; i < PROPS.length; i++) {
              var o = saved[PROPS[i]];
              if (o[0]) pinned.style.setProperty(PROPS[i], o[0], o[1]); else pinned.style.removeProperty(PROPS[i]);
            }
            pinned = null; saved = null; key = '';
          }
          function hold(e) {
            if (pinned === e) return;
            unpin();
            // Its own size, from before it was pinned: pinned at the screen's size it would
            // always look the largest, and a game that turns up later could never take over.
            var nw = e.offsetWidth, nh = e.offsetHeight;
            pinned = e; saved = { __w: nw, __h: nh };
            for (var i = 0; i < PROPS.length; i++) saved[PROPS[i]] = [e.style.getPropertyValue(PROPS[i]), e.style.getPropertyPriority(PROPS[i])];
            var root = document.documentElement, body = document.body;
            // The page at 1:1: a page wider than the screen is otherwise zoomed out to fit, and the
            // screen is then more than the page's own viewport (the game came out too small).
            var vp = document.querySelector('meta[name=viewport]');
            if (!vp) { vp = document.createElement('meta'); vp.name = 'viewport'; (document.head || root).appendChild(vp); }
            vp.setAttribute('content', 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no');
            root.style.background = '#000'; body.style.background = '#000';
            root.style.overflow = 'hidden'; body.style.overflow = 'hidden';
            window.scrollTo(0, 0);
          }
          function selfScaling(e) {
            var t = e.tagName;
            return t === 'IFRAME' || t === 'FRAME' || t === 'EMBED' || t === 'OBJECT' || t.indexOf('RUFFLE-') === 0;
          }
          // Social and ad widgets are never the game (Facebook's start out 1000 px wide).
          var WIDGET = /(^|\.)(facebook\.com|facebook\.net|fbcdn\.net|twitter\.com|x\.com|google\.com|googlesyndication\.com|doubleclick\.net|googleadservices\.com|amazon-adsystem\.com|adnxs\.com|addthis\.com|sharethis\.com|disqus\.com|criteo\.com|taboola\.com|outbrain\.com)$/i;
          function widget(e) {
            if (e.tagName !== 'IFRAME' || !e.src) return false;
            try { return WIDGET.test(new URL(e.src, location.href).hostname); } catch (x) { return false; }
          }
          function pick() {
            var best = null, area = 0;
            var all = document.querySelectorAll('canvas, video, iframe, frame, embed, object, ruffle-player, ruffle-embed, ruffle-object');
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              if (widget(el)) continue;
              var w = el === pinned ? saved.__w : el.offsetWidth, h = el === pinned ? saved.__h : el.offsetHeight;
              if (w < 160 || h < 120) continue;
              if (w * h > area) { area = w * h; best = el; }
            }
            return best;
          }
          // A canvas's wrapper of its own size (with the game's overlays) moves with it.
          function box(e) {
            var b = e, w = e.offsetWidth, h = e.offsetHeight;
            for (var p = e.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
              if (p.offsetWidth > w * 1.08 + 4 || p.offsetHeight > h * 1.08 + 4 || p.offsetWidth < w - 4) break;
              b = p;
            }
            return b;
          }
          function fit() {
            if (!document.body) return;
            var m = mode();
            if (m === 'off') { unpin(); return; }
            if (pinned && !document.contains(pinned)) { pinned = null; saved = null; key = ''; }
            var e = pick();
            if (!e) return;
            // What is on screen: the visual viewport (and where it sits in the page's viewport).
            var vv = window.visualViewport;
            var vw = vv ? vv.width : window.innerWidth, vh = vv ? vv.height : window.innerHeight;
            var ox = vv ? vv.offsetLeft : 0, oy = vv ? vv.offsetTop : 0;
            if (selfScaling(e)) {
              var sk = [vw, vh, ox, oy].join();
              if (pinned === e && sk === key) return;
              hold(e);
              key = sk;
              set(e, { position: 'fixed', left: ox + 'px', top: oy + 'px', right: 'auto', bottom: 'auto', width: vw + 'px', height: vh + 'px',
                'max-width': 'none', 'max-height': 'none', margin: '0', border: '0', transform: 'none', 'z-index': '2147483647' });
              return;
            }
            var b = pinned && pinned.contains(e) ? pinned : box(e);
            if (pinned === b) {
              if ([b.offsetWidth, b.offsetHeight, vw, vh, ox, oy, m].join() === key) return;
              // Its size changed (the game laid itself out again): look again, unpinned.
              unpin();
            }
            var w = b.offsetWidth, h = b.offsetHeight;
            // Already fitted to the window by the game itself (on screen, centred, filling it one
            // way, as TurboWarp's Scratch player and Phaser's FIT do): nothing to do.
            var r = b.getBoundingClientRect();
            var inside = r.left >= -2 && r.top >= -2 && r.right <= vw + 2 && r.bottom <= vh + 2;
            var centred = Math.abs(r.left + r.width / 2 - vw / 2) <= vw * 0.06 && Math.abs(r.top + r.height / 2 - vh / 2) <= vh * 0.1;
            if (inside && centred && (r.width >= vw * 0.85 || r.height >= vh * 0.85)) return;
            var kx = vw / w, ky = vh / h, ax = Math.min(kx, ky), ay = ax;
            if (m === 'stretch') { ax = kx; ay = ky; }
            else if (m === 'fill') { ax = ay = Math.max(kx, ky); }
            var tx = ox + (vw - w * ax) / 2, ty = oy + (vh - h * ay) / 2;
            hold(b);
            set(b, { position: 'fixed', left: '0', top: '0', right: 'auto', bottom: 'auto', margin: '0',
              'transform-origin': '0 0', transform: 'translate(' + tx + 'px,' + ty + 'px) scale(' + ax + ',' + ay + ')', 'z-index': '2147483647' });
            // Measured as pinned, so a size that only differs because of the pinning is not a change.
            key = [b.offsetWidth, b.offsetHeight, vw, vh, ox, oy, m].join();
          }
          window.addEventListener('load', fit);
          window.addEventListener('resize', function() { key = ''; fit(); });
          if (window.visualViewport) window.visualViewport.addEventListener('resize', function() { key = ''; fit(); });
          setInterval(fit, 1000);
        })();
    """.trimIndent()

    /** Scale mode and quality for Ruffle's web build, as a document-start script. */
    fun displayJs(scale: String, quality: String): String {
        val parts = mutableListOf("\"quality\":\"$quality\"")
        // Browser pages: fit the game element to the screen, unless Original size asks for the page.
        parts += "\"pageFit\":\"" + when (scale) { "stretch" -> "stretch"; "fill" -> "fill"; "original" -> "off"; else -> "fit" } + "\""
        when (scale) {
            "fit" -> parts += listOf("\"scale\":\"showAll\"", "\"forceScale\":true")
            "stretch" -> parts += listOf("\"scale\":\"exactFit\"", "\"forceScale\":true", "\"letterbox\":\"off\"")
            "fill" -> parts += listOf("\"scale\":\"noBorder\"", "\"forceScale\":true", "\"letterbox\":\"off\"")
            "original" -> parts += listOf("\"scale\":\"noScale\"", "\"forceScale\":true")
        }
        return "window.__fpmDisplay={" + parts.joinToString(",") + "};"
    }

    fun playerUrl(swfUrl: String): String {
        val u = Uri.parse(swfUrl)
        return Uri.Builder().scheme(u.scheme ?: "http").encodedAuthority(u.encodedAuthority)
            .path("/__fpm/player.html").appendQueryParameter("swf", swfUrl).build().toString()
    }

    fun intercept(ctx: Context, url: Uri): WebResourceResponse? {
        val path = url.path ?: return null
        if (!path.startsWith(PREFIX)) return null
        if (path == "${PREFIX}player.html") {
            val swf = url.getQueryParameter("swf").orEmpty()
            return html(playerHtml(swf))
        }
        val asset = path.removePrefix(PREFIX)
        if (asset.contains("..")) return null
        return try {
            val mime = when {
                asset.endsWith(".wasm") -> "application/wasm"
                asset.endsWith(".js") -> "text/javascript"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, null, ctx.assets.open(asset)).apply {
                responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun html(s: String) = WebResourceResponse("text/html", "utf-8", s.byteInputStream())

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""

    private fun playerHtml(swf: String) = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden;touch-action:none}
        #p{position:fixed;inset:0}ruffle-player{width:100%;height:100%;display:block}</style>
        <script>if(!window.__fpmRuffle){window.__fpmRuffle=true;window.RufflePlayer=window.RufflePlayer||{};
        window.RufflePlayer.config=Object.assign($CONFIG,window.__fpmDisplay||{});document.write('<script src="/__fpm/ruffle/ruffle.js"><\/script>');}</script>
        </head><body><div id="p"></div><script>
        (function(){
          var url = ${jsString(swf)};
          function go(){
            if (!window.RufflePlayer || !window.RufflePlayer.newest) return setTimeout(go, 20);
            var player = window.RufflePlayer.newest().createPlayer();
            document.getElementById('p').appendChild(player);
            var api = player.ruffle ? player.ruffle() : player;
            api.load({ url: url, base: url.substring(0, url.lastIndexOf('/') + 1) });
            player.focus();
          }
          go();
        })();
        </script></body></html>
    """.trimIndent()
}
