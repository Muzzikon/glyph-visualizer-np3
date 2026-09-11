package com.example.glyphvisualizer

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var activeMediaAppPackage: String? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach { updateActivePlayer(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        updateActivePlayer(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == activeMediaAppPackage) {
            activeMediaAppPackage = null
            try {
                activeNotifications?.forEach { updateActivePlayer(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            notifyServiceStateChanged()
        }
    }

    private fun updateActivePlayer(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return

        val notif = sbn.notification ?: return
        val extras = notif.extras

        val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                extras.containsKey("android.mediaSession") ||
                notif.category == Notification.CATEGORY_TRANSPORT ||
                sbn.isOngoing ||
                notif.actions?.any { action ->
                    val title = action.title?.toString()?.lowercase() ?: ""
                    title.contains("play") || title.contains("pause") ||
                            title.contains("next") || title.contains("prev") ||
                            title.contains("пауз") || title.contains("воспр") ||
                            title.contains("стоп") || title.contains("след")
                } == true

        if (isMedia && activeMediaAppPackage != pkg) {
            activeMediaAppPackage = pkg
            notifyServiceStateChanged()
        }
    }

    private fun notifyServiceStateChanged() {
        val intent = Intent(this, GlyphVisualizerService::class.java).apply {
            putExtra("ACTION", "RELOAD_APPS")
        }
        try {
            startService(intent)
        } catch (_: Exception) {}
    }
}