package com.danlearnsstuff.network

import android.content.Intent
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

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
 * (per-episode offline downloads). [requestRefresh] is the relay entry
 * point for the v0.2.0 resume check: the iframe posts
 * `dlsn-requestRefresh` when it becomes visible again with the triple
 * guard holding, and native reloads once per resume event at most.
 *
 * v0.2.2 — the bridge is injected only into the share page's TOP frame,
 * while the app lives in the artifact iframe. When that iframe is
 * cross-origin, `window.parent.DLSNBridge` throws from inside the iframe
 * and none of the methods above is ever reached. The fix is a postMessage
 * relay (RELAY_JS in MainActivity): the iframe posts requests to the top
 * frame, the relay calls the bridge methods there, and results are posted
 * back into the iframe. Relay entry points are [fetchFeedRelay],
 * [downloadEpisodeRelay] and [requestRefresh]; [onState], [openLink],
 * [isDownloaded] and [deleteDownload] are called by the relay directly.
 * The direct JavascriptInterface methods stay as the same-origin fallback.
 * Feed results are now compact episode JSON parsed natively (see
 * [parseFeedToCompactJson]), not raw XML — the web side must JSON.parse
 * the payload.
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
     * on success `ok` is true and `payload` is compact episode JSON (see
     * [parseFeedToCompactJson]); on failure `ok` is false and `payload`
     * is the error message. `requestId` is echoed so the page can match
     * the result to its request. `user`/`pass` are sent as Basic auth
     * when non-empty (paid feeds, e.g. Never Not Funny) — they are passed
     * through only, never logged and never stored.
     */
    @JavascriptInterface
    fun fetchFeed(url: String, user: String, pass: String, requestId: String) {
        fetchFeedInternal(url, user, pass, requestId, viaRelay = false)
    }

    /**
     * v0.2.2 — relay entry point for [fetchFeed]: identical fetch, but
     * the result is delivered through the top-frame postMessage relay
     * (`window.__dlsnRelayReply`) instead of the direct
     * `window.__dlsnFeedResult` callback, so it reaches the app even
     * when the artifact iframe is cross-origin with the share page.
     * Called by the relay JS in MainActivity, never directly by the web
     * app. Covers both feed paths — opening an imported show AND the
     * add-form validation (including paid feeds with user/pass).
     */
    @JavascriptInterface
    fun fetchFeedRelay(url: String, user: String, pass: String, requestId: String) {
        fetchFeedInternal(url, user, pass, requestId, viaRelay = true)
    }

    private fun fetchFeedInternal(
        url: String, user: String, pass: String, requestId: String, viaRelay: Boolean
    ) {
        // Credentials are passed through to Basic auth only — never logged.
        Log.d(TAG, "fetchFeed url=$url requestId=$requestId viaRelay=$viaRelay")
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
                    // v0.2.2 — parse natively and return compact JSON, never
                    // the raw XML (which ran near the ~1 MB binder limit).
                    val compactJson = parseFeedToCompactJson(text)
                    reportFeedResult(requestId, true, compactJson, viaRelay)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                reportFeedResult(requestId, false, e.message ?: e.toString(), viaRelay)
            }
        }.start()
    }

    private fun reportFeedResult(requestId: String, ok: Boolean, payload: String, viaRelay: Boolean) {
        Log.d(TAG, "reportFeedResult requestId=$requestId ok=$ok payloadBytes=${payload.length} viaRelay=$viaRelay")
        val js = if (viaRelay) {
            // Post back through the relay, which forwards into the iframe.
            "window.__dlsnRelayReply && window.__dlsnRelayReply(" +
                JSONObject.quote("feed") + "," + JSONObject.quote(requestId) + "," + ok + "," +
                JSONObject.quote(payload) + ")"
        } else {
            "window.__dlsnFeedResult(" +
                JSONObject.quote(requestId) + "," + ok + "," +
                JSONObject.quote(payload) + ")"
        }
        activity.evalJs(js)
    }

    /**
     * v0.2.2 — parse RSS 2.0 / Atom natively (Android's built-in
     * XmlPullParser, no new dependencies) and return compact episode
     * JSON the web side parses instead of raw XML:
     *
     * `{"feedTitle": "...", "feedDescription": "...",
     *   "episodes": [{"title": "...", "audioUrl": "...",
     *   "pubDate": "...", "duration": "...", "guid": "..."}]}`
     *
     * Only the first [MAX_EPISODES] episodes are kept (feeds list
     * newest first) and the payload is shrunk to under
     * [MAX_FEED_JSON_CHARS], so the result is a few KB at most — never
     * the ~951 KB of raw XML that used to travel through
     * evaluateJavascript. `duration` is the raw `itunes:duration` string
     * (e.g. "1:23:45"); empty when the feed has none. `pubDate` is the
     * raw `pubDate` / `published` string. Throws on malformed XML, which
     * the caller reports as a fetch failure.
     */
    private fun parseFeedToCompactJson(xml: String): String {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        var feedTitle = ""
        var feedDescription = ""
        val episodes = ArrayList<JSONObject>()
        var inEpisode = false
        var isAtomEntry = false
        var itemTitle = ""
        var itemAudio = ""
        var itemDate = ""
        var itemDuration = ""
        var itemGuid = ""
        var textField: String? = null
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name ?: ""
                    when {
                        name == "item" -> {
                            inEpisode = true; isAtomEntry = false
                            itemTitle = ""; itemAudio = ""; itemDate = ""
                            itemDuration = ""; itemGuid = ""
                            textField = null
                        }
                        name == "entry" -> {
                            inEpisode = true; isAtomEntry = true
                            itemTitle = ""; itemAudio = ""; itemDate = ""
                            itemDuration = ""; itemGuid = ""
                            textField = null
                        }
                        name == "enclosure" && inEpisode -> {
                            val u = parser.getAttributeValue(null, "url")
                            if (!u.isNullOrEmpty() && itemAudio.isEmpty()) itemAudio = u
                        }
                        name == "link" && inEpisode && isAtomEntry -> {
                            val rel = parser.getAttributeValue(null, "rel")
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrEmpty() &&
                                (rel == "enclosure" ||
                                    (itemAudio.isEmpty() && href.endsWith(".mp3", ignoreCase = true)))
                            ) itemAudio = href
                        }
                        name == "title" -> textField = if (inEpisode) "title" else "feedTitle"
                        name == "description" && !inEpisode -> textField = "feedDescription"
                        name == "subtitle" && !inEpisode -> textField = "feedDescription"
                        name == "pubDate" && inEpisode -> textField = "pubDate"
                        name == "published" && inEpisode -> textField = "pubDate"
                        name == "updated" && inEpisode -> textField = "updated"
                        name == "duration" && inEpisode -> textField = "duration"
                        name == "guid" && inEpisode -> textField = "guid"
                        name == "id" && inEpisode && isAtomEntry -> textField = "guid"
                        else -> textField = null
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text
                    if (!t.isNullOrEmpty()) {
                        when (textField) {
                            "title" -> itemTitle += t
                            "feedTitle" -> feedTitle += t
                            "feedDescription" -> feedDescription += t
                            "pubDate" -> itemDate += t
                            "updated" -> if (itemDate.isEmpty()) itemDate += t
                            "duration" -> itemDuration += t
                            "guid" -> itemGuid += t
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name ?: ""
                    if ((name == "item" || name == "entry") && inEpisode) {
                        if (itemTitle.isNotEmpty() || itemAudio.isNotEmpty()) {
                            episodes.add(JSONObject().apply {
                                put("title", itemTitle.trim().take(300))
                                put("audioUrl", itemAudio.trim().take(2000))
                                put("pubDate", itemDate.trim().take(100))
                                put("duration", itemDuration.trim().take(20))
                                put("guid", itemGuid.trim().take(500))
                            })
                        }
                        inEpisode = false
                        isAtomEntry = false
                    }
                    textField = null
                }
            }
            eventType = parser.next()
        }
        val root = JSONObject()
        root.put("feedTitle", feedTitle.trim().take(300))
        root.put("feedDescription", feedDescription.trim().take(1000))
        val arr = JSONArray()
        for (ep in episodes.take(MAX_EPISODES)) arr.put(ep)
        root.put("episodes", arr)
        var json = root.toString()
        while (json.length > MAX_FEED_JSON_CHARS && arr.length() > 0) {
            arr.remove(arr.length() - 1)
            json = root.toString()
        }
        return json
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

    /**
     * v0.2.2 — relay entry point for [downloadEpisode]: identical
     * download, but completion is reported through the top-frame
     * postMessage relay (`window.__dlsnRelayReply`) instead of the
     * direct `window.__dlsnDownloadResult` callback. Called by the
     * relay JS in MainActivity, never directly by the web app.
     */
    @JavascriptInterface
    fun downloadEpisodeRelay(url: String, key: String, title: String) {
        try {
            activity.downloadHelper.enqueue(url, key, title, viaRelay = true)
        } catch (e: Exception) {
            activity.evalJs(
                "window.__dlsnRelayReply && window.__dlsnRelayReply(" +
                    JSONObject.quote("download") + "," + JSONObject.quote(key) +
                    ",false," + JSONObject.quote(e.message ?: "Download failed") + ")"
            )
        }
    }

    /**
     * v0.2.2 — relay entry point for the resume check. Called by the
     * top-frame relay JS when the iframe posts
     * `{type:'dlsn-requestRefresh'}`. The iframe only posts it when the
     * document becomes visible again AND the triple guard holds (My
     * Shows list visible, no series open, nothing playing — the same
     * conditions `__dlsnResumeCheck` tests), so this never fires on
     * initial page load and never interrupts playback. The actual reload
     * and the once-per-resume guard live in
     * [MainActivity.requestRefreshFromRelay]; this method just forwards.
     * No reply message — the reload itself is the response.
     */
    @JavascriptInterface
    fun requestRefresh() {
        activity.requestRefreshFromRelay()
    }

    /**
     * v0.2.2 — called by the top-frame relay JS so relay traffic shows
     * in logcat (tag `DLSN`): `direction` is "in" (a postMessage request
     * arrived from the iframe) or "out" (a reply was posted into the
     * iframe), `type` is the message type, `id` the requestId/key.
     * This is what makes "bridge never called" vs "callback lost"
     * distinguishable next time.
     */
    @JavascriptInterface
    fun relayLog(direction: String, type: String, id: String) {
        Log.d(TAG, "relay $direction type=$type id=$id")
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
        /** Logcat tag for all bridge diagnostics (v0.2.2). */
        const val TAG = "DLSN"

        /** Desktop-ish User-Agent for native fetches/downloads. */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** Feed bodies are capped at ~2 MB. */
        const val MAX_FEED_BYTES = 2 * 1024 * 1024

        /** v0.2.2 — at most this many episodes in the compact feed JSON. */
        const val MAX_EPISODES = 200

        /** v0.2.2 — compact feed JSON is shrunk to stay under this many chars. */
        const val MAX_FEED_JSON_CHARS = 180_000

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
