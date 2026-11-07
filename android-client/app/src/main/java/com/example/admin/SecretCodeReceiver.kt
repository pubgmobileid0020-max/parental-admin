package com.example.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "android.provider.Telephony.SECRET_CODE") {
            StealthManager.toggle(context)
        }
    }
}
