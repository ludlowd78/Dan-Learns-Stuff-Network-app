package com.danlearnsstuff.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground service that keeps episode audio alive with the screen locked
 * and owns the lock-screen MediaSession.
 *
 * Why this exists: Android's WebView has no MediaSession API of its own and
 * the OS suspends a plain app's WebView when the screen goes off, which is
 * why playback in the Muse app stops ~3 seconds after lock. A foreground
 * service of type mediaPlayback keeps the process alive, and the native
 * MediaSession here gives the lock screen real play/pause/seek controls.
 * The audio itself still comes from the WebView's `<audio>` element — the
 * service only keeps it alive and mirrors its state. See README.md for the
 * trade-offs and the native-player fallback if this ever proves unreliable.
 */
class PlaybackService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false
    private var lastState: PlaybackState? = null

    /** Artwork URL currently being (or already) fetched as a bitmap. */
    private var artworkFetchUrl: String? = null

    /** Fetched cover bitmap for [artworkFetchUrl], if the fetch succeeded. */
    private var artworkBitmap: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        mediaSession = MediaSessionCompat(this, "DLSN").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    PlaybackStateBus.controller?.play()
                }

                override fun onPause() {
                    PlaybackStateBus.controller?.pause()
                }

                override fun onSeekTo(pos: Long) {
                    PlaybackStateBus.controller?.seekTo(pos)
                }

                override fun onSkipToNext() {
                    PlaybackStateBus.controller?.seekBy(SKIP_MS)
                }

                override fun onSkipToPrevious() {
                    PlaybackStateBus.controller?.seekBy(-SKIP_MS)
                }

                override fun onFastForward() {
                    PlaybackStateBus.controller?.seekBy(SKIP_MS)
                }

                override fun onRewind() {
                    PlaybackStateBus.controller?.seekBy(-SKIP_MS)
                }
            })
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@PlaybackService, 0, launchIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
            isActive = true
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "DLSN:playback"
        )

        // A service started with startForegroundService() must call
        // startForeground() within seconds, so promote immediately with a
        // placeholder notification; onPlaybackState() replaces it with the
        // real episode details as soon as the bridge reports them.
        goForeground(buildNotification(PLACEHOLDER_STATE))

        PlaybackStateBus.listener = { state -> onPlaybackState(state) }
        PlaybackStateBus.last?.let { onPlaybackState(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlaybackStateBus.controller?.play()
            ACTION_PAUSE -> PlaybackStateBus.controller?.pause()
            ACTION_FF -> PlaybackStateBus.controller?.seekBy(SKIP_MS)
            ACTION_RW -> PlaybackStateBus.controller?.seekBy(-SKIP_MS)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from Recents: the activity (and its WebView audio) is
        // gone, so there is nothing left to keep alive.
        stopSelf()
    }

    override fun onDestroy() {
        PlaybackStateBus.listener = null
        mediaSession.isActive = false
        mediaSession.release()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun onPlaybackState(state: PlaybackState) {
        lastState = state

        // Reuse the bitmap fetched for this artwork URL, if we have one;
        // a new URL resets it (see maybeFetchArtwork).
        val bitmap = if (state.artwork == artworkFetchUrl) artworkBitmap else null
        mediaSession.setMetadata(buildMetadata(state, bitmap))

        val playbackState = PlaybackStateCompat.Builder()
            .setState(
                if (state.playing) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED,
                state.positionMs, 1.0f
            )
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND
            )
            .build()
        mediaSession.setPlaybackState(playbackState)

        val notification = buildNotification(state)
        if (state.playing) {
            acquireWakeLock()
            goForeground(notification)
            isForeground = true
        } else {
            releaseWakeLock()
            if (isForeground) {
                // Drop foreground priority but keep the notification up so
                // the user can resume from the lock screen.
                stopForeground(false)
                isForeground = false
            }
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        }

        // ITEM 4 (artwork, best-effort) — fetch the cover as a real bitmap
        // so the lock screen shows it reliably. Never blocks the
        // notification above: this only starts a background fetch.
        maybeFetchArtwork(state)
    }

    private fun buildMetadata(state: PlaybackState, artworkBitmap: Bitmap?): MediaMetadataCompat {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Dan Learns Stuff Network")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
        if (state.artwork.isNotEmpty()) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, state.artwork)
        }
        if (artworkBitmap != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkBitmap)
        }
        return builder.build()
    }

    /**
     * ITEM 4 (artwork, best-effort) — when the episode artwork is an
     * http(s) URL, fetch it on a background thread (4s timeouts, downscaled
     * to max 512px) and, when it lands, put the real bitmap in the
     * MediaSession metadata (METADATA_KEY_ALBUM_ART) and rebuild the
     * notification. Deduped per URL: onPlaybackState runs on every bridge
     * report, so a fetch only starts when the artwork URL changes. If the
     * fetch fails or times out, the URI behaviour above is kept and
     * nothing is retried until the artwork URL changes. If the episode
     * changed while the fetch was in flight, the bitmap is discarded.
     */
    private fun maybeFetchArtwork(state: PlaybackState) {
        val url = state.artwork
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) return
        if (url == artworkFetchUrl) return
        artworkFetchUrl = url
        artworkBitmap = null
        Thread {
            val bitmap = fetchArtworkBitmap(url)
            if (bitmap != null) {
                Handler(Looper.getMainLooper()).post {
                    val current = lastState ?: return@post
                    if (current.artwork != url) return@post
                    artworkBitmap = bitmap
                    mediaSession.setMetadata(buildMetadata(current, bitmap))
                    val notification = buildNotification(current)
                    if (current.playing) {
                        goForeground(notification)
                    } else {
                        NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
                    }
                }
            }
        }.start()
    }

    private fun fetchArtworkBitmap(url: String): Bitmap? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", AudioBridge.DESKTOP_USER_AGENT)
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > MAX_ARTWORK_PX ||
                bounds.outHeight / sample > MAX_ARTWORK_PX
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(state: PlaybackState): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val playPauseAction = if (state.playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                servicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                servicePendingIntent(ACTION_PLAY)
            )
        }
        val rewindAction = NotificationCompat.Action(
            android.R.drawable.ic_media_rew, "-15s", servicePendingIntent(ACTION_RW)
        )
        val forwardAction = NotificationCompat.Action(
            android.R.drawable.ic_media_ff, "+15s", servicePendingIntent(ACTION_FF)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(state.title.ifEmpty { "Dan Learns Stuff Network" })
            .setContentText(state.artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(state.playing)
            .addAction(rewindAction)
            .addAction(playPauseAction)
            .addAction(forwardAction)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ITEM 4 — new channel id at DEFAULT importance. An update
            // cannot change an existing channel's importance (the old
            // IMPORTANCE_LOW channel hid the notification from Samsung's
            // lock screen), hence the new id; the old channel is orphaned
            // and unused.
            val channel = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Now-playing controls for the Dan Learns Stuff Network, shown on the lock screen" }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        // Held without a timeout and released on pause: a timeout would
        // drop it mid-episode.
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
    }

    companion object {
        const val CHANNEL_ID = "dlsn_playback_v2"
        const val NOTIF_ID = 1
        const val SKIP_MS = 15_000L
        /** Lock-screen artwork bitmaps are downscaled to at most this size. */
        const val MAX_ARTWORK_PX = 512
        const val ACTION_PLAY = "com.danlearnsstuff.network.PLAY"
        const val ACTION_PAUSE = "com.danlearnsstuff.network.PAUSE"
        const val ACTION_FF = "com.danlearnsstuff.network.FF"
        const val ACTION_RW = "com.danlearnsstuff.network.RW"
        val PLACEHOLDER_STATE = PlaybackState(
            playing = true,
            positionMs = 0,
            durationMs = 0,
            title = "Dan Learns Stuff Network",
            artist = "Dan Learns Stuff Network",
            artwork = ""
        )
    }
}
