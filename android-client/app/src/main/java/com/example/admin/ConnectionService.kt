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
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {
    companion object {
        const val ACTION_STOP = "com.example.admin.STOP"
        const val ACTION_STATUS = "com.example.admin.STATUS"
        const val EXTRA_CONNECTED = "connected"
        
        @Volatile var instance: ConnectionService? = null
        @Volatile var isWsConnected = false
        var screenResultCode = 0
        var screenData: Intent? = null
        
        fun push(ctx: Context, kind: String, data: String) { instance?.sendPushInternal(kind, data) }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
        
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
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel("conn", "Service", NotificationManager.IMPORTANCE_MIN)
        nm.createNotificationChannel(channel)
        
        val notif = buildNotification("Running...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1001, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shouldRun = false; reconnectJob?.cancel(); pingJob?.cancel(); try { ws?.close(1000, "stop") } catch (_: Exception) {}
            stopForeground(true); stopSelf(); return START_NOT_STICKY
        }
        shouldRun = true; Prefs.setRunning(this, true); connect(); return START_STICKY
    }

    private fun connect() {
        if (!shouldRun) return
        val server = Prefs.getServer(this).trim()
        val psk = Prefs.getPsk(this).trim()
        if (server.isBlank() || psk.isBlank()) return
        
        val wsUrl = buildWsUrl(server)
        ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    private fun buildWsUrl(server: String): String {
        var s = server.trimEnd('/')
        if (s.startsWith("https://")) s = "wss://" + s.removePrefix("https://")
        else if (s.startsWith("http://")) s = "ws://" + s.removePrefix("http://")
        else if (!s.startsWith("ws://") && !s.startsWith("wss://")) s = "wss://$s"
        if (!s.endsWith("/ws")) s = "$s/ws"
        return s
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isWsConnected = true
            broadcastStatus(true)
            
            val deviceId = Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            val customName = Prefs.getDeviceName(this@ConnectionService)
            val deviceName = if (customName.isBlank()) "Device" else customName
            
            val hello = JSONObject()
                .put("role", "device")
                .put("psk", Prefs.getPsk(this@ConnectionService))
                .put("device_id", deviceId)
                .put("device_name", deviceName)
                
            webSocket.send(hello.toString())
            updateNotification("Connected")
            
            // PING EVERY 5 SECONDS
            pingJob?.cancel()
            pingJob = scope.launch {
                while (isActive) {
                    delay(5000)
                    try {
                        webSocket.send(JSONObject().put("type", "ping").toString())
                    } catch (_: Exception) {}
                }
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            backoff = 2000L 
            scope.launch {
                try {
                    val obj = JSONObject(text)
                    if (obj.optString("type") == "cmd") {
                        val id = obj.optString("id"); val cmd = obj.optString("cmd"); val args = obj.optJSONObject("args") ?: JSONObject()
                        
                        // IMMEDIATELY REPLY BACK SO THE SERVER DOESN'T TIMEOUT
                        val reply = JSONObject().put("type", "reply").put("id", id).put("ok", true)
                        webSocket.send(reply.toString())
                        
                        // Then execute the command in the background
                        CommandHandler.dispatch(this@ConnectionService, cmd, args)
                    }
                } catch (_: Exception) {}
            }
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { 
            isWsConnected = false
            broadcastStatus(false)
            updateNotification("Reconnecting...")
            scheduleReconnect() 
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { 
            isWsConnected = false
            broadcastStatus(false)
            if (shouldRun) scheduleReconnect() 
        }
    }

    private fun broadcastStatus(connected: Boolean) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_CONNECTED, connected)
        }
        sendBroadcast(intent)
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        reconnectJob?.cancel()
        val d = backoff
        backoff = (backoff * 2).coerceAtMost(60_000L)
        reconnectJob = scope.launch { 
            delay(d)
            connect() 
        }
    }

    fun sendPushInternal(kind: String, data: String) {
        val payload = JSONObject().put("type", "push").put("kind", kind).put("data", data).toString()
        try { ws?.send(payload) } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification = Notification.Builder(this, "conn")
        .setContentTitle("Device Manager")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .setPriority(Notification.PRIORITY_MIN)
        .build()

    private fun updateNotification(text: String) { 
        try { getSystemService(NotificationManager::class.java).notify(1001, buildNotification(text)) } catch (_: Exception) {} 
    }

    override fun onDestroy() {
        shouldRun = false; reconnectJob?.cancel(); pingJob?.cancel(); try { ws?.close(1000, "destroy") } catch (_: Exception) {}
        instance = null; isWsConnected = false; Prefs.setRunning(this, false); super.onDestroy()
    }
}
