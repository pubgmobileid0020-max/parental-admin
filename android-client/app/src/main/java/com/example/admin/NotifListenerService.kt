package com.example.admin

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NotifListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        // Ignore system UI and our own app to prevent infinite loops
        if (s.packageName == "android" || s.packageName == applicationContext.packageName) return

        val ex = s.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        
        if (title.isBlank() && text.isBlank()) return

        val obj = JSONObject()
            .put("pkg", s.packageName)
            .put("title", title)
            .put("text", text)
        ConnectionService.push(applicationContext, "notification", obj.toString())
    }
}
