package com.danlearnsstuff.network

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Per-episode downloads for offline listening (NEXT-UPDATE item 7).
 *
 * Downloads go through [DownloadManager] into app-specific external
 * storage — `Music/dlsn/<key>.mp3` under getExternalFilesDir() — so no
 * storage permission is needed. On completion the episode is exposed to
 * the web app as a FileProvider `content://` URI, which the page swaps
 * in as the episode's audio `src` for offline playback (the WebView has
 * `allowContentAccess = true` for exactly this).
 *
 * The receiver only lives while the activity does: a download that
 * finishes after the activity is gone simply stays on disk, and the web
 * app sees it next launch via `DLSNBridge.isDownloaded(key)`.
 */
class DownloadHelper(private val activity: MainActivity) {

    private val downloadManager =
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** DownloadManager id -> sanitised episode key + relay channel, for in-flight downloads. */
    private val pending = mutableMapOf<Long, PendingDownload>()

    /** v0.2.2 — which channel the completion result must go back on. */
    private data class PendingDownload(val key: String, val viaRelay: Boolean)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != -1L) onDownloadComplete(id)
        }
    }
    private var receiverRegistered = false

    fun register() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                activity,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    fun unregister() {
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered — ignore.
            }
            receiverRegistered = false
        }
    }

    fun downloadDir(): File =
        File(activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "dlsn")
            .apply { mkdirs() }

    fun fileFor(key: String): File = File(downloadDir(), "${sanitizeKey(key)}.mp3")

    fun isDownloaded(key: String): Boolean = fileFor(key).exists()

    /** Deletes the downloaded file if present; returns true if a file was removed. */
    fun deleteDownload(key: String): Boolean {
        val file = fileFor(key)
        return file.exists() && file.delete()
    }

    /**
     * Enqueue a download. `viaRelay` (v0.2.2) routes the completion
     * callback through the top-frame postMessage relay instead of the
     * direct `window.__dlsnDownloadResult` callback — it must match the
     * channel the request arrived on.
     */
    fun enqueue(url: String, key: String, title: String, viaRelay: Boolean = false) {
        val safeKey = sanitizeKey(key)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("Dan Learns Stuff Network")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                activity, Environment.DIRECTORY_MUSIC, "dlsn/$safeKey.mp3"
            )
            .addRequestHeader("User-Agent", AudioBridge.DESKTOP_USER_AGENT)
        val id = downloadManager.enqueue(request)
        synchronized(pending) { pending[id] = PendingDownload(safeKey, viaRelay) }
        register()
    }

    private fun onDownloadComplete(id: Long) {
        val download: PendingDownload = synchronized(pending) { pending.remove(id) } ?: return
        val key = download.key
        val file = fileFor(key)
        var success = false
        var error = "Download failed"
        val cursor: Cursor? = downloadManager.query(DownloadManager.Query().setFilterById(id))
        cursor?.use {
            if (it.moveToFirst()) {
                val status = it.getInt(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                if (status == DownloadManager.STATUS_SUCCESSFUL && file.exists()) {
                    success = true
                } else {
                    val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIndex >= 0) it.getInt(reasonIndex) else -1
                    error = "Download failed (status=$status, reason=$reason)"
                }
            }
        }
        if (success) {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            reportResult(key, true, uri.toString(), download.viaRelay)
        } else {
            reportResult(key, false, error, download.viaRelay)
        }
    }

    private fun reportResult(key: String, ok: Boolean, payload: String, viaRelay: Boolean) {
        Log.d(AudioBridge.TAG, "downloadResult key=$key ok=$ok viaRelay=$viaRelay")
        val js = if (viaRelay) {
            // Post back through the relay, which forwards into the iframe.
            "window.__dlsnRelayReply && window.__dlsnRelayReply(" +
                JSONObject.quote("download") + "," + JSONObject.quote(key) + "," + ok + "," +
                JSONObject.quote(payload) + ")"
        } else {
            "window.__dlsnDownloadResult(" +
                JSONObject.quote(key) + "," + ok + "," + JSONObject.quote(payload) + ")"
        }
        activity.evalJs(js)
    }

    companion object {
        /** Keep download filenames to safe characters (no path traversal). */
        fun sanitizeKey(key: String): String =
            key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "episode" }
    }
}
