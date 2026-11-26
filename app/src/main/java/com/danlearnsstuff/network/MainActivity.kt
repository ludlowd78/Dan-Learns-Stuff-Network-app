package com.danlearnsstuff.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
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

    /**
     * v0.2.2 — per-resume reload guard: true once this resume event has
     * already triggered a reload, whether through the direct
     * `__dlsnResumeCheck` path or the `dlsn-requestRefresh` relay path.
     * Reset at the top of every [onResume]; at most one reload per
     * resume event, so a late relay message can never double-reload.
     */
    private var resumeReloadDone = false

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
                // v0.2.2 — postMessage relay: the app iframe posts bridge
                // requests to this top frame (the bridge is injected here
                // only), the relay calls the bridge, and results are
                // posted back into the iframe. Without it, a cross-origin
                // iframe can never reach DLSNBridge at all.
                view.evaluateJavascript(RELAY_JS, null)
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
        // v0.2.2 — reset the per-resume reload guard (see
        // requestRefreshFromRelay): at most one reload per resume event.
        resumeReloadDone = false
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
            if (result == "true") {
                resumeReloadDone = true
                webView.reload()
            }
        }
    }

    /**
     * v0.2.2 — relay path for the resume check. Called by
     * [AudioBridge.requestRefresh] when the top-frame relay receives a
     * `dlsn-requestRefresh` postMessage from the app iframe. The iframe
     * only posts it when the document becomes visible again AND its own
     * triple guard holds (My Shows list visible, no series open,
     * nothing playing — the same `__dlsnResumeCheck` conditions), so
     * this is the cross-origin equivalent of the direct
     * `__dlsnResumeCheck` evaluateJavascript path in [onResume], which
     * is kept as the same-origin fallback. Performs the same reload,
     * under the same native guards (page loaded, nothing playing) and
     * the same per-resume guard: if this resume event already reloaded
     * (either path), this is a no-op.
     */
    fun requestRefreshFromRelay() {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (!pageLoaded) return@runOnUiThread
            if (PlaybackStateBus.last?.playing == true) return@runOnUiThread
            if (resumeReloadDone) return@runOnUiThread
            resumeReloadDone = true
            Log.d("DLSN", "resume refresh via relay")
            webView.reload()
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

        /**
         * v0.2.2 — postMessage relay between the app iframe and the native
         * bridge. The native `DLSNBridge` JavascriptInterface is injected
         * only into the share page's TOP frame, while the app lives in the
         * artifact iframe; when that iframe is cross-origin,
         * `window.parent.DLSNBridge` throws from inside it, so the native
         * fetch is never called and the direct-fetch fallback fails as it
         * always has. The relay closes that gap:
         *
         * Iframe -> top frame (window.postMessage to window.parent):
         *   {type:'dlsn-fetchFeed', url, user, pass, requestId}
         *   {type:'dlsn-openLink', url}
         *   {type:'dlsn-downloadEpisode', url, key, title}
         *   {type:'dlsn-isDownloaded', key, requestId}
         *   {type:'dlsn-deleteDownload', key, requestId}
         *   {type:'dlsn-state', playing, positionMs, durationMs,
         *    title, artist, artwork}
         *   {type:'dlsn-requestRefresh'}  (no fields — see below)
         *
         * `dlsn-requestRefresh` is the cross-origin half of the v0.2.0
         * "refresh on menu open" behaviour: the direct
         * `window.__dlsnResumeCheck()` evaluateJavascript in onResume
         * runs in the top frame, where that function does not exist
         * when the artifact iframe is cross-origin, so the check
         * silently never fires. The iframe instead posts this message
         * when it becomes visible again and its own triple guard holds
         * (My Shows list visible, no series open, nothing playing —
         * the same conditions `__dlsnResumeCheck` tests). Native
         * reloads once per resume event at most (per-resume flag in
         * MainActivity, reset on each onResume) and re-checks that
         * nothing is playing before reloading. No reply message.
         * The direct `__dlsnResumeCheck` path is kept as the
         * same-origin fallback.
         *
         * Top frame -> iframe (iframe.contentWindow.postMessage):
         *   {type:'dlsn-feedResult', requestId, ok, payload}
         *   {type:'dlsn-downloadResult', key, ok, payload}
         *   {type:'dlsn-isDownloadedResult', key, requestId, downloaded}
         *   {type:'dlsn-deleteDownloadResult', key, requestId, ok}
         *
         * Origin is validated loosely: the message must come from this
         * page's own origin, or from one of its iframes (event.source
         * match). The request shape is validated too; anything else is
         * ignored. Credentials in dlsn-fetchFeed are passed straight
         * through to the native fetch (Basic auth) and are never logged
         * or stored natively.
         *
         * The relay is additive: the direct `DLSNBridge`
         * JavascriptInterface and the direct `window.__dlsn*` callbacks
         * keep working as the same-origin fallback. Replies always go
         * back through the channel the request arrived on — postMessage
         * requests get postMessage replies; direct bridge calls keep
         * their existing `__dlsn*` callbacks.
         *
         * Diagnostics: every relay request received and every reply
         * posted is logged via bridge.relayLog (logcat tag `DLSN`).
         */
        val RELAY_JS: String = """
            (function () {
              if (window.__dlsnRelayInstalled) return;
              window.__dlsnRelayInstalled = true;
              var bridge = window.DLSNBridge;

              function logRelay(direction, type, id) {
                try {
                  if (bridge && bridge.relayLog) bridge.relayLog(direction, type, id || '');
                } catch (e) {}
              }

              // The app iframe: the largest visible iframe on the share
              // page (the artifact frame). Falls back to the first iframe.
              function appFrame() {
                var frames = document.querySelectorAll('iframe');
                var best = null, bestArea = 0;
                for (var i = 0; i < frames.length; i++) {
                  var r = frames[i].getBoundingClientRect();
                  var area = r.width * r.height;
                  if (area > bestArea) { bestArea = area; best = frames[i]; }
                }
                return best || frames[0] || null;
              }

              function postToApp(msg) {
                var f = appFrame();
                if (!f || !f.contentWindow) return false;
                try {
                  f.contentWindow.postMessage(msg, '*');
                  logRelay('out', msg.type, msg.requestId || msg.key || '');
                  return true;
                } catch (e) { return false; }
              }

              // Native -> iframe: called by Kotlin (AudioBridge /
              // DownloadHelper) via evaluateJavascript when a relayed
              // request completes. Replies always go back through
              // postMessage, never through a direct callback.
              window.__dlsnRelayReply = function (kind, id, ok, payload) {
                var msg;
                if (kind === 'download') {
                  msg = { type: 'dlsn-downloadResult', key: id, ok: !!ok, payload: payload };
                } else {
                  msg = { type: 'dlsn-feedResult', requestId: id, ok: !!ok, payload: payload };
                }
                postToApp(msg);
              };

              function originOk(ev) {
                try {
                  if (ev.origin === window.location.origin) return true;
                  var frames = document.querySelectorAll('iframe');
                  for (var i = 0; i < frames.length; i++) {
                    if (frames[i].contentWindow === ev.source) return true;
                  }
                } catch (e) {}
                return false;
              }

              window.addEventListener('message', function (ev) {
                var d = ev.data;
                if (!d || typeof d !== 'object' || typeof d.type !== 'string') return;
                if (!originOk(ev)) return;
                if (!bridge) return;
                logRelay('in', d.type, d.requestId || d.key || '');
                try {
                  switch (d.type) {
                    case 'dlsn-fetchFeed':
                      // user/pass pass straight through to the native
                      // fetch (Basic auth); never logged, never stored.
                      if (typeof d.url === 'string' && typeof d.requestId === 'string') {
                        bridge.fetchFeedRelay(d.url, d.user || '', d.pass || '', d.requestId);
                      }
                      break;
                    case 'dlsn-openLink':
                      if (typeof d.url === 'string') bridge.openLink(d.url);
                      break;
                    case 'dlsn-downloadEpisode':
                      if (typeof d.url === 'string' && typeof d.key === 'string') {
                        bridge.downloadEpisodeRelay(d.url, d.key, d.title || '');
                      }
                      break;
                    case 'dlsn-isDownloaded':
                      if (typeof d.key === 'string' && typeof d.requestId === 'string') {
                        var downloaded = false;
                        try { downloaded = !!bridge.isDownloaded(d.key); } catch (e) {}
                        postToApp({ type: 'dlsn-isDownloadedResult', key: d.key, requestId: d.requestId, downloaded: downloaded });
                      }
                      break;
                    case 'dlsn-deleteDownload':
                      if (typeof d.key === 'string' && typeof d.requestId === 'string') {
                        try { bridge.deleteDownload(d.key); } catch (e) {}
                        postToApp({ type: 'dlsn-deleteDownloadResult', key: d.key, requestId: d.requestId, ok: true });
                      }
                      break;
                    case 'dlsn-state':
                      if (typeof d.playing === 'boolean') {
                        bridge.onState(!!d.playing, d.positionMs | 0, d.durationMs | 0,
                          d.title || '', d.artist || '', d.artwork || '');
                      }
                      break;
                    case 'dlsn-requestRefresh':
                      // v0.2.2 — the iframe's visibilitychange listener
                      // fires this when the app becomes visible again and
                      // the triple guard holds (My Shows list visible, no
                      // series open, nothing playing). Same reload the
                      // direct __dlsnResumeCheck path performs; the
                      // per-resume guard in Kotlin keeps it to one reload
                      // per resume event, and native re-checks that
                      // nothing is playing first. No fields, no reply.
                      bridge.requestRefresh();
                      break;
                  }
                } catch (e) {}
              });
            })();
        """.trimIndent()
    }
}
