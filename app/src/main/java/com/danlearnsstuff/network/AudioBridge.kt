package com.danlearnsstuff.network

import android.content.Intent
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bridge between the web player's `<audio>` element and the native side.
 *
 * The network app is a web page, so the native app cannot reach its audio
 * element directly. [INJECTED_JS] runs in the page after load: it finds the
 * audio element, reports play/pause/position to [PlaybackStateBus], and
 * exposes `window.__dlsnControl(cmd, value)` so the native MediaSession can
 * drive playback back (play, pause, seek, skip +/-15s).
 *
 * Title/artwork are best-effort: the page's own Media Session metadata is
 * read first (it works in Chrome, where the page sets it), then the player
 * DOM is scraped for a title and cover image, and finally the document
 * title is used. If the web app's markup changes, the worst case is a
 * generic title on the lock screen — playback itself is unaffected.
 *
 * The bridge also exposes native capabilities the page cannot do itself:
 * [fetchFeed] (fetch a podcast RSS feed outside the browser, so imported
 * podcasts can load episodes), [openLink] (open a URL in the external
 * browser), and [downloadEpisode]/[isDownloaded]/[deleteDownload]
 * (per-episode offline downloads).
 */
class AudioBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun onState(
        playing: Boolean,
        positionMs: Int,
        durationMs: Int,
        title: String,
        artist: String,
        artwork: String
    ) {
        val state = PlaybackState(
            playing = playing,
            positionMs = positionMs.toLong(),
            durationMs = durationMs.toLong(),
            title = title,
            artist = artist,
            artwork = artwork
        )
        activity.runOnUiThread {
            if (playing) activity.ensurePlaybackService()
            PlaybackStateBus.post(state)
        }
    }

    /**
     * ITEM 8b — open a URL in the phone's default browser. Called from the
     * web app's Sources-link handler when the bridge is present. Never
     * navigates any frame to an external URL.
     */
    @JavascriptInterface
    fun openLink(url: String) {
        try {
            activity.runOnUiThread {
                activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) { /* no browser available — ignore */ }
    }

    /**
     * ITEM 6 — fetch a podcast RSS/Atom feed natively, outside the
     * browser, so imported podcasts can load episodes (in-page fetches
     * fail from the share-page frame). Runs on a background thread and
     * reports back via `window.__dlsnFeedResult(requestId, ok, payload)`:
     * on success `ok` is true and `payload` is the feed body as UTF-8
     * text (capped at ~2 MB); on failure `ok` is false and `payload` is
     * the error message. `requestId` is echoed so the page can match the
     * result to its request.
     */
    @JavascriptInterface
    fun fetchFeed(url: String, user: String, pass: String, requestId: String) {
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 12_000
                    connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                    if (user.isNotEmpty()) {
                        val credentials = Base64.encodeToString(
                            "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                        )
                        connection.setRequestProperty("Authorization", "Basic $credentials")
                    }
                    connection.connect()
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    connection.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            val remaining = MAX_FEED_BYTES - total
                            if (remaining <= 0) break
                            val take = minOf(n, remaining)
                            out.write(buffer, 0, take)
                            total += take
                            if (total >= MAX_FEED_BYTES) break
                        }
                    }
                    val text = out.toString(Charsets.UTF_8.name())
                    reportFeedResult(requestId, true, text)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                reportFeedResult(requestId, false, e.message ?: e.toString())
            }
        }.start()
    }

    private fun reportFeedResult(requestId: String, ok: Boolean, payload: String) {
        activity.evalJs(
            "window.__dlsnFeedResult(" +
                JSONObject.quote(requestId) + "," + ok + "," +
                JSONObject.quote(payload) + ")"
        )
    }

    /**
     * ITEM 7 — download one episode for offline listening. The download
     * runs in DownloadManager; when it completes the page is called back
     * with `window.__dlsnDownloadResult(key, ok, payload)`, where on
     * success `payload` is the FileProvider `content://` URI of the
     * downloaded file (the page swaps the episode `src` to it to play
     * offline) and on failure it is an error message.
     */
    @JavascriptInterface
    fun downloadEpisode(url: String, key: String, title: String) {
        try {
            activity.downloadHelper.enqueue(url, key, title)
        } catch (e: Exception) {
            activity.evalJs(
                "window.__dlsnDownloadResult(" +
                    JSONObject.quote(key) + ",false," +
                    JSONObject.quote(e.message ?: "Download failed") + ")"
            )
        }
    }

    /** ITEM 7 — true when the episode file for [key] is already downloaded. */
    @JavascriptInterface
    fun isDownloaded(key: String): Boolean {
        return try {
            activity.downloadHelper.isDownloaded(key)
        } catch (e: Exception) {
            false
        }
    }

    /** ITEM 7 — delete the downloaded file for [key], if present. */
    @JavascriptInterface
    fun deleteDownload(key: String) {
        try {
            activity.downloadHelper.deleteDownload(key)
        } catch (e: Exception) {
            // Nothing downloaded (or helper not ready) — ignore.
        }
    }

    companion object {
        /** Desktop-ish User-Agent for native fetches/downloads. */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** Feed bodies are capped at ~2 MB. */
        const val MAX_FEED_BYTES = 2 * 1024 * 1024

        val INJECTED_JS: String = """
            (function () {
              if (window.__dlsnBridgeInstalled) return;
              window.__dlsnBridgeInstalled = true;
              var bridge = window.DLSNBridge;
              var audio = null;
              var lastTimeReport = 0;

              function pickText(selectors) {
                for (var i = 0; i < selectors.length; i++) {
                  var el = document.querySelector(selectors[i]);
                  if (el && el.textContent && el.textContent.trim()) {
                    return el.textContent.trim();
                  }
                }
                return '';
              }

              function report(now) {
                if (!audio || !bridge) return;
                if (!now) {
                  var t = Date.now();
                  if (t - lastTimeReport < 1000) return;
                  lastTimeReport = t;
                }
                var title = '';
                var artist = 'Dan Learns Stuff Network';
                var art = '';
                try {
                  if (navigator.mediaSession && navigator.mediaSession.metadata) {
                    var m = navigator.mediaSession.metadata;
                    if (m.title) title = m.title;
                    if (m.artist) artist = m.artist;
                    if (m.artwork && m.artwork.length && m.artwork[0].src) art = m.artwork[0].src;
                  }
                } catch (e) {}
                if (!title) {
                  title = pickText([
                    '[class*="player"] [class*="title"]',
                    '[class*="episode-title"]',
                    '[class*="now-playing"] [class*="title"]',
                    '[class*="now-playing"]'
                  ]);
                }
                if (!title) title = document.title || 'Dan Learns Stuff Network';
                if (!art) {
                  var img = document.querySelector(
                    '[class*="player"] img, [class*="cover"] img, img[class*="cover"]'
                  );
                  if (img) art = img.currentSrc || img.src || '';
                }
                var dur = audio.duration;
                if (!isFinite(dur)) dur = 0;
                bridge.onState(
                  !audio.paused,
                  Math.round(audio.currentTime * 1000),
                  Math.round(dur * 1000),
                  title, artist, art
                );
              }

              function hook(a) {
                if (!a || a.__dlsnHooked) return;
                a.__dlsnHooked = true;
                audio = a;
                ['play', 'pause', 'ended', 'loadedmetadata', 'playing', 'loadstart'].forEach(function (ev) {
                  a.addEventListener(ev, function () { report(true); });
                });
                a.addEventListener('timeupdate', function () { report(false); });
                report(true);
              }

              function scan() {
                var list = document.querySelectorAll('audio');
                for (var i = 0; i < list.length; i++) hook(list[i]);
              }

              // ITEM 4 — the app itself lives inside an iframe on the Muse
              // share page, so the top-frame scan above never sees its
              // <audio> and the bridge never reported "playing". Also scan
              // inside same-origin iframes (reusing hook(), so the same
              // onState reporting applies). Cross-origin iframes throw on
              // contentDocument access and are skipped silently. This is
              // the fallback for the web app reporting its state directly
              // from inside the iframe; both report to the same onState,
              // which is idempotent, so there is no conflict.
              function scanFrames() {
                var frames = document.querySelectorAll('iframe');
                for (var i = 0; i < frames.length; i++) {
                  try {
                    var doc = frames[i].contentDocument;
                    if (!doc) continue;
                    var list = doc.querySelectorAll('audio');
                    for (var j = 0; j < list.length; j++) hook(list[j]);
                  } catch (e) {}
                }
              }

              new MutationObserver(function () { scan(); scanFrames(); }).observe(document.documentElement, {
                childList: true, subtree: true
              });
              scan();
              scanFrames();

              window.__dlsnControl = function (cmd, val) {
                if (!audio) scan();
                if (!audio) scanFrames();
                if (!audio) return;
                try {
                  if (cmd === 'play') audio.play();
                  else if (cmd === 'pause') audio.pause();
                  else if (cmd === 'seekTo') audio.currentTime = val / 1000;
                  else if (cmd === 'seekBy') {
                    audio.currentTime = Math.max(0, audio.currentTime + val / 1000);
                  }
                } catch (e) {}
              };
            })();
        """.trimIndent()
    }
}
