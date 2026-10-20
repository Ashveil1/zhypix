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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        try {
            val extras = n.notification.extras
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString() } catch (e: Exception) { n.packageName }
            log(this).add(label, extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                (n.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0, System.currentTimeMillis())
        } catch (e: Exception) { /* never crash the listener */ }
    }
}
