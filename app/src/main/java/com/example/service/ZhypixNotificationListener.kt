package com.example.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.agent.JsonListStore
import com.example.agent.NotificationLog
import java.io.File

/** Local-only notification history. The user must enable it in Android's notification access settings. */
class ZhypixNotificationListener : NotificationListenerService() {
    companion object {
        fun log(context: android.content.Context) =
            NotificationLog(JsonListStore(File(context.applicationContext.filesDir, "notifications.json")))
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "notif-log").apply { isDaemon = true } }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        val extras = n.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val ongoing = (n.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val pkg = n.packageName
        io.execute {
            try {
                val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                log(this).add(label, title, text, ongoing, System.currentTimeMillis())
            } catch (e: Exception) { /* never crash the listener */ }
        }
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
