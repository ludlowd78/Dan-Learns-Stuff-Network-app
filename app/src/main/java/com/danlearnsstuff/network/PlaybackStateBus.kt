package com.danlearnsstuff.network

/**
 * Shared state between the WebView (via [AudioBridge]) and [PlaybackService].
 *
 * The WebView reports playback state through [post]; the service listens and
 * mirrors it into its MediaSession and notification. The service drives the
 * web audio back through [controller], which MainActivity implements with
 * evaluateJavascript calls.
 *
 * Limitation, stated plainly: the controller belongs to the activity's
 * WebView. If the activity is destroyed (for example the user swipes the app
 * away from Recents), the audio element is gone too, so lock-screen controls
 * stop working. Backgrounding with the Home button or locking the screen
 * keeps the activity alive, and that is the case this app is built for.
 */
object PlaybackStateBus {
    var controller: AudioController? = null
    var listener: ((PlaybackState) -> Unit)? = null

    /** Most recent state, so a late-starting listener can catch up. */
    var last: PlaybackState? = null
        private set

    fun post(state: PlaybackState) {
        last = state
        listener?.invoke(state)
    }
}

data class PlaybackState(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val title: String,
    val artist: String,
    val artwork: String
)

/** Commands the native side can send to the web player's audio element. */
interface AudioController {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
}
