package com.danlearnsstuff.network

import android.webkit.JavascriptInterface

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

    companion object {
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

              new MutationObserver(scan).observe(document.documentElement, {
                childList: true, subtree: true
              });
              scan();

              window.__dlsnControl = function (cmd, val) {
                if (!audio) scan();
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
