package com.danlearnsstuff.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The web player starts playback from a user tap, but resume-after-
        // reload and auto-advance also need to play without a fresh gesture.
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        webView.addJavascriptInterface(AudioBridge(this), "DLSNBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(AudioBridge.INJECTED_JS, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host.endsWith("muse.ai")) return false
                // Anything that is not the network app opens in the browser.
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }
        webView.webChromeClient = WebChromeClient()

        PlaybackStateBus.controller = this
        requestNotificationPermission()
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
    }

    // Deliberately no webView.onPause() here: pausing the WebView pauses its
    // audio, which would stop playback the moment the screen locks.
    // PlaybackService (foreground, mediaPlayback type) keeps the process
    // alive while audio is playing instead.

    override fun onDestroy() {
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
    }
}
