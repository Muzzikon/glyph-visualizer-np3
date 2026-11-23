package com.example.glyphvisualizer

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Один controller/Binder callback на живую media session, а не на обновление уведомления. */
class MediaNotificationListener : NotificationListenerService() {
    companion object { @Volatile var activeMediaAppPackage: String? = null; private set
        @Volatile internal var playback:PlaybackTrack?=null;private set }
    private data class Entry(val controller: MediaController, val callback: MediaController.Callback)
    private val controllers = linkedMapOf<MediaSession.Token, Entry>()
    private val destroyedTokens = mutableSetOf<MediaSession.Token>()
    private val main = Handler(Looper.getMainLooper())
    private var connected = false
    private var refreshQueued = false
    private var createdCount = 0
    private var removedCount = 0
    private val refreshTask = Runnable {
        refreshQueued = false
        if (connected) refresh()
    }

    override fun onListenerConnected() { connected = true; scheduleRefresh(0) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || mediaToken(sbn) != null || controllers.values.any { it.controller.packageName == sbn.packageName }) scheduleRefresh()
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || mediaToken(sbn) != null || controllers.values.any { it.controller.packageName == sbn.packageName }) scheduleRefresh()
    }
    override fun onListenerDisconnected() {
        connected = false
        main.removeCallbacks(refreshTask); refreshQueued = false
        clear(); activeMediaAppPackage = null;playback=null
    }
    private fun scheduleRefresh(delay: Long = 100L) {
        if (!connected || refreshQueued) return
        refreshQueued = true
        main.postDelayed(refreshTask, delay)
    }
    @Suppress("DEPRECATION")
    private fun mediaToken(sbn: StatusBarNotification): MediaSession.Token? =
        try { sbn.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) }
        catch (error: RuntimeException) { Log.w("GlyphMedia", "Invalid media notification", error); null }

    private fun remove(token: MediaSession.Token) {
        val entry = controllers.remove(token) ?: return
        try { entry.controller.unregisterCallback(entry.callback) }
        catch (error: Exception) { Log.w("GlyphMedia", "Unregister media callback", error) }
        removedCount++
    }
    private fun clear() {
        controllers.keys.toList().forEach { remove(it) }
        destroyedTokens.clear()
    }
    private fun refresh() {
        val wanted = linkedSetOf<MediaSession.Token>()
        try {
            for (sbn in activeNotifications ?: emptyArray()) {
                if (sbn.packageName == packageName) continue
                mediaToken(sbn)?.let { wanted.add(it) }
            }
        } catch (error: SecurityException) {
            clear(); activeMediaAppPackage = null;playback=null
            Log.w("GlyphMedia", "Notification access unavailable", error)
            return
        }
        controllers.keys.filter { it !in wanted }.forEach { remove(it) }
        destroyedTokens.retainAll(wanted)
        var changed = false
        for (token in wanted) {
            if (controllers.containsKey(token) || token in destroyedTokens) continue
            try {
                val controller = MediaController(this, token)
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (connected && controllers[token]?.controller === controller) choosePlaying()
                    }
                    override fun onMetadataChanged(metadata:MediaMetadata?) {if(connected && controllers[token]?.controller === controller)choosePlaying()}
                    override fun onSessionDestroyed() {
                        if (controllers[token]?.controller === controller) {
                            remove(token); destroyedTokens.add(token)
                            choosePlaying(); scheduleRefresh()
                        }
                    }
                }
                controller.registerCallback(callback, main)
                controllers[token] = Entry(controller, callback)
                createdCount++; changed = true
            } catch (error: Exception) { Log.w("GlyphMedia", "Register media callback", error) }
        }
        choosePlaying()
        if (changed) Log.d("GlyphMedia", "Sessions ${controllers.size}; created $createdCount; removed $removedCount")
    }
    private fun choosePlaying() {
        val playing=controllers.values.filter {
            try {it.controller.playbackState?.state==PlaybackState.STATE_PLAYING}catch(_:Exception){false}
        }
        // The global Visualizer output mixes sources. Never attribute concurrent playback
        // to whichever session happens to be first, including when checking the whitelist.
        val controller=playing.singleOrNull()?.controller
        activeMediaAppPackage=controller?.packageName
        playback=if(controller==null)null else try {
            val state=controller.playbackState
            val metadata=controller.metadata
            val mediaId=metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
            val fallbackUri=if(mediaId.startsWith("file:") || mediaId.startsWith("content:") || mediaId.startsWith("/"))mediaId else ""
            if(state==null || metadata==null)null else PlaybackTrack(controller.packageName,controller.sessionToken.hashCode(),
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)?.takeIf{it.isNotBlank()} ?: fallbackUri,
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),state.position,state.lastPositionUpdateTime,state.playbackSpeed,true)
        }catch(_:Exception){null}
    }
    override fun onDestroy() {
        onListenerDisconnected()
        super.onDestroy()
    }
}
