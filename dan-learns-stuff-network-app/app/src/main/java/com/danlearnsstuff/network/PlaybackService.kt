package com.danlearnsstuff.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

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

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Dan Learns Stuff Network")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
        if (state.artwork.isNotEmpty()) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, state.artwork)
        }
        mediaSession.setMetadata(metadata.build())

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
            val channel = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Now-playing controls for the Dan Learns Stuff Network" }
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
        const val CHANNEL_ID = "dlsn_playback"
        const val NOTIF_ID = 1
        const val SKIP_MS = 15_000L
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
