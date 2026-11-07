package com.example.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        if (Prefs.getServer(context).isBlank() || Prefs.getPsk(context).isBlank()) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, ConnectionService::class.java)
        )
    }
}
