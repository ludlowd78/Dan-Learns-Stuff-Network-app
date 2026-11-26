package com.danlearnsstuff.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The whole UI is the network web app, loaded in a WebView.
 *
 * Two things in here matter for lock-screen playback and are deliberate:
 * 1. We never call webView.onPause() when the activity pauses. Pausing the
 *    WebView would pause its media pipeline, which is exactly what kills
 *    audio when the screen locks. The [PlaybackService] foreground service
 *    keeps the process alive instead.
 * 2. The service is started the moment audio starts playing (see
 *    [ensurePlaybackService]), not at launch: a service started with
 *    startForegroundService() must call startForeground() within seconds,
 *    and there is nothing to keep foreground until playback begins.
 *    [PlaybackService] promotes itself to foreground immediately on start
 *    and then mirrors the real playback state.
 */
class MainActivity : AppCompatActivity(), AudioController {

    private lateinit var webView: WebView
    lateinit var downloadHelper: DownloadHelper

    /** True once the share page has loaded at least once (see onResume). */
    private var pageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // ITEM 5 — respect the status-bar safe area so WebView content
        // starts below the status bar instead of running up under it.
        // The padding is set to an absolute value (baseline + inset) on
        // every dispatch, never added to the current padding, so it can
        // never accumulate; and if the system is already insetting this
        // view (fitsSystemWindows), we leave its padding alone.
        val basePaddingTop = webView.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            if (!ViewCompat.getFitsSystemWindows(v)) {
                val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.setPadding(
                    v.paddingLeft, basePaddingTop + statusTop,
                    v.paddingRight, v.paddingBottom
                )
            }
            insets
        }

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The web player starts playback from a user tap, but resume-after-
        // reload and auto-advance also need to play without a fresh gesture.
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        // ITEM 7 — needed so the <audio> element can play downloaded
        // episodes served as FileProvider content:// URIs.
        settings.allowContentAccess = true
        settings.setSupportMultipleWindows(true)

        webView.addJavascriptInterface(AudioBridge(this), "DLSNBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                view.evaluateJavascript(AudioBridge.INJECTED_JS, null)
                // ITEM 1 — hide the Muse share page's own header. Runs in
                // the share page's top frame; never touches the app iframe.
                view.evaluateJavascript(HEADER_HIDE_JS, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host.endsWith("muse.ai")) return false
                // Anything that is not the network app opens in the browser.
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // ITEM 8a — target="_blank" links (episode Sources links, the
            // App link, feed links) open in the phone's default browser.
            // We never navigate any frame to an external URL ourselves.
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val transport = resultMsg.obj as WebView.WebViewTransport
                val transientView = WebView(view.context)
                transientView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView, request: WebResourceRequest
                    ): Boolean {
                        try {
                            view.context.startActivity(
                                Intent(Intent.ACTION_VIEW, request.url)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                            // No browser available — ignore.
                        }
                        return true
                    }
                }
                transport.webView = transientView
                resultMsg.sendToTarget()
                return true
            }
        }

        downloadHelper = DownloadHelper(this)
        downloadHelper.register()

        PlaybackStateBus.controller = this
        requestNotificationPermission()
        // ITEM 10 — clear the WebView HTTP cache once on a version bump,
        // before the first load, so a re-shared page shows fresh. This
        // does NOT touch localStorage: episode positions and imported
        // feeds live there and are preserved.
        clearCacheOnVersionChange()
        webView.loadUrl(getString(R.string.app_url))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // ITEM 2 — refresh on resume, but only when all three hold:
        // (a) the page has loaded at least once, so this never reloads on
        // first launch; (b) nothing is playing; (c) the page itself says
        // it is on the main menu (My Shows list, no series open, nothing
        // playing). The reload never cuts off playback.
        if (!pageLoaded) return
        if (PlaybackStateBus.last?.playing == true) return
        webView.evaluateJavascript(
            "window.__dlsnResumeCheck ? window.__dlsnResumeCheck() : false"
        ) { result ->
            if (result == "true") webView.reload()
        }
    }

    // Deliberately no webView.onPause() here: pausing the WebView pauses its
    // audio, which would stop playback the moment the screen locks.
    // PlaybackService (foreground, mediaPlayback type) keeps the process
    // alive while audio is playing instead.

    override fun onDestroy() {
        if (::downloadHelper.isInitialized) downloadHelper.unregister()
        PlaybackStateBus.controller = null
        webView.destroy()
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    /**
     * ITEM 10 — if the app version changed since the last launch, clear
     * the WebView HTTP cache once so a re-shared page is not served
     * stale. clearCache(true) clears the HTTP cache only; localStorage
     * and app data are untouched.
     */
    private fun clearCacheOnVersionChange() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastVersion = prefs.getInt(KEY_LAST_VERSION_CODE, -1)
        val currentVersion = currentVersionCode()
        if (lastVersion != currentVersion) {
            webView.clearCache(true)
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersion).apply()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Int {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }

    /**
     * Called from [AudioBridge] whenever the web player reports it is
     * playing. Starting the service is idempotent — if it is already
     * running this just redelivers the intent.
     */
    fun ensurePlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** Evaluate JS in the page (used by bridge callbacks: feed/download results). */
    fun evalJs(js: String) {
        runOnUiThread {
            if (!isDestroyed) {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    // ---- AudioController: MediaSession commands -> the web player's audio element ----

    override fun play() = runJs("__dlsnControl('play')")

    override fun pause() = runJs("__dlsnControl('pause')")

    override fun seekTo(positionMs: Long) = runJs("__dlsnControl('seekTo',$positionMs)")

    override fun seekBy(deltaMs: Long) = runJs("__dlsnControl('seekBy',$deltaMs)")

    private fun runJs(js: String) {
        runOnUiThread {
            if (!isDestroyed) {
                webView.evaluateJavascript("try{ $js }catch(e){}", null)
            }
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
        private const val PREFS_NAME = "dlsn_prefs"
        private const val KEY_LAST_VERSION_CODE = "last_version_code"

        /**
         * ITEM 1 — hides the Muse share page's own header (artifact title
         * + "Sign in"/"Sign up") from the top of the WebView.
         *
         * Runs in the share page's TOP frame only (onPageFinished). It
         * hides elements outright — no overlay div — so the space goes
         * back to the app. Defensive, in order:
         *  (a) hide any <header> element;
         *  (b) hide the element holding a "Sign in"/"Sign up" button/link
         *      (its <header>/<nav> ancestor when there is one);
         *  (c) belt-and-braces: hide every direct child of <body> except
         *      the subtree containing the app iframe, walking from the
         *      iframe up and hiding siblings at each level.
         * It never hides the iframe itself or anything inside it (hide()
         * refuses any element that is, or contains, an iframe), and a
         * MutationObserver re-applies it when the share page renders late.
         * This also makes the stale "Through The Doorway" header moot.
         */
        val HEADER_HIDE_JS: String = """
            (function () {
              if (window.__dlsnHeaderHideInstalled) return;
              window.__dlsnHeaderHideInstalled = true;

              function hide(el) {
                if (!el || el === document.documentElement || el === document.body) return;
                if (el.tagName === 'IFRAME') return;
                if (el.querySelector && el.querySelector('iframe')) return;
                el.style.setProperty('display', 'none', 'important');
              }

              function hideShareHeader() {
                var i, j, k;
                var headers = document.querySelectorAll('header');
                for (i = 0; i < headers.length; i++) hide(headers[i]);

                var controls = document.querySelectorAll('a, button');
                for (j = 0; j < controls.length; j++) {
                  var t = (controls[j].textContent || '').trim();
                  if (/sign\s*in/i.test(t) || /sign\s*up/i.test(t)) {
                    var node = controls[j];
                    var hidden = false;
                    var up = node;
                    for (k = 0; k < 4 && up.parentElement; k++) {
                      up = up.parentElement;
                      if (up.tagName === 'HEADER' || up.tagName === 'NAV') {
                        hide(up);
                        hidden = true;
                        break;
                      }
                    }
                    if (!hidden) hide(node);
                  }
                }

                var frames = document.querySelectorAll('iframe');
                for (var f = 0; f < frames.length; f++) {
                  var node = frames[f];
                  while (node && node !== document.body) {
                    var parent = node.parentElement;
                    if (!parent) break;
                    var kids = parent.children;
                    for (var c = 0; c < kids.length; c++) {
                      if (kids[c] !== node) hide(kids[c]);
                    }
                    node = parent;
                  }
                }
              }

              try {
                var style = document.createElement('style');
                style.textContent = 'header{display:none !important;}';
                if (document.head) document.head.appendChild(style);
              } catch (e) {}

              hideShareHeader();
              new MutationObserver(hideShareHeader).observe(document.documentElement, {
                childList: true, subtree: true
              });
            })();
        """.trimIndent()
    }
}
