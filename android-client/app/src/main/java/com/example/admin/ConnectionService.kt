package com.example.admin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {
    companion object {
        const val ACTION_STOP = "com.example.admin.STOP"
        const val ACTION_STATUS = "com.example.admin.STATUS"
        @Volatile var instance: ConnectionService? = null
        @Volatile var isWsConnected = false
        var screenResultCode = 0
        var screenData: Intent? = null
        fun push(ctx: Context, kind: String, data: String) { instance?.sendPushInternal(kind, data) }
    }

    private val client = OkHttpClient.Builder().pingInterval(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backoff = 2000L
    private var shouldRun = false
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("conn", "Service", NotificationManager.IMPORTANCE_MIN))
        val notif = Notification.Builder(this, "conn").setContentTitle("Device Manager").setContentText("Running...").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).setPriority(Notification.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1001, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { 
            shouldRun = false
            reconnectJob?.cancel()
            pingJob?.cancel()
            try { ws?.close(1000, "stop") } catch (_: Exception) {} 
            
            // FIXED FOR ANDROID 14
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY 
        }
        shouldRun = true; Prefs.setRunning(this, true); connect(); return START_STICKY
    }

    private fun connect() {
        if (!shouldRun) return
        val server = Prefs.getServer(this).trim(); val psk = Prefs.getPsk(this).trim()
        if (server.isBlank() || psk.isBlank()) return
        var s = server.trimEnd('/'); if (s.startsWith("https://")) s = "wss://" + s.removePrefix("https://") else if (!s.startsWith("ws")) s = "wss://$s"; if (!s.endsWith("/ws")) s = "$s/ws"
        ws = client.newWebSocket(Request.Builder().url(s).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isWsConnected = true; sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
            val hello = JSONObject().put("role", "device").put("psk", Prefs.getPsk(this@ConnectionService)).put("device_id", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown").put("device_name", Prefs.getDeviceName(this@ConnectionService).ifBlank { "Device" })
            webSocket.send(hello.toString())
            pingJob?.cancel(); pingJob = scope.launch { while (isActive) { delay(5000); try { webSocket.send("{\"type\":\"ping\"}") } catch (_: Exception) {} } }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            backoff = 2000L; scope.launch {
                try { val obj = JSONObject(text); if (obj.optString("type") == "cmd") {
                    webSocket.send(JSONObject().put("type", "reply").put("id", obj.optString("id")).put("ok", true).toString())
                    CommandHandler.dispatch(this@ConnectionService, obj.optString("cmd"), obj.optJSONObject("args") ?: JSONObject())
                } } catch (_: Exception) {}
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { isWsConnected = false; sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)); scheduleReconnect() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { isWsConnected = false; sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)); if (shouldRun) scheduleReconnect() }
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        reconnectJob?.cancel(); val d = backoff; backoff = (backoff * 2).coerceAtMost(60000L)
        reconnectJob = scope.launch { delay(d); connect() }
    }

    fun sendPushInternal(kind: String, data: String) { try { ws?.send(JSONObject().put("type", "push").put("kind", kind).put("data", data).toString()) } catch (_: Exception) {} }

    override fun onDestroy() {
        shouldRun = false; reconnectJob?.cancel(); pingJob?.cancel(); try { ws?.close(1000, "destroy") } catch (_: Exception) {}
        instance = null; isWsConnected = false; Prefs.setRunning(this, false); super.onDestroy()
    }
}
