package com.example.admin

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
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

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backoff = 1000L
    private var shouldRun = false
    private var reconnectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel("conn", "Service", NotificationManager.IMPORTANCE_MIN)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
        startForeground(1001, buildNotification("Running..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shouldRun = false; reconnectJob?.cancel(); try { ws?.close(1000, "stop") } catch (_: Exception) {}
            stopForeground(true); stopSelf(); return START_NOT_STICKY
        }
        shouldRun = true; Prefs.setRunning(this, true); connect(); return START_STICKY
    }

    // THIS FORCES THE SERVICE TO RESTART IF SWIPED AWAY
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartService = Intent(applicationContext, this.javaClass)
        restartService.setPackage(packageName)
        val pendingIntent = PendingIntent.getService(
            this, 1, restartService, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pendingIntent)
            }
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pendingIntent)
        }
        super.onTaskRemoved(rootIntent)
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
            backoff = 1000L
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
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                try {
                    val obj = JSONObject(text)
                    if (obj.optString("type") == "cmd") {
                        val id = obj.optString("id"); val cmd = obj.optString("cmd"); val args = obj.optJSONObject("args") ?: JSONObject()
                        val res = CommandHandler.dispatch(this@ConnectionService, cmd, args)
                        val reply = JSONObject().put("type", "reply").put("id", id).put("ok", res.optBoolean("ok", false))
                        if (res.has("data")) reply.put("data", res.opt("data"))
                        if (res.has("error")) reply.put("error", res.optString("error"))
                        webSocket.send(reply.toString())
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
        val d = backoff; backoff = (backoff * 2).coerceAtMost(60_000L)
        reconnectJob = scope.launch { delay(d); connect() }
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
        .setSilent(true)
        .build()

    private fun updateNotification(text: String) { 
        try { getSystemService(NotificationManager::class.java).notify(1001, buildNotification(text)) } catch (_: Exception) {} 
    }

    override fun onDestroy() {
        shouldRun = false; reconnectJob?.cancel(); try { ws?.close(1000, "destroy") } catch (_: Exception) {}
        instance = null; isWsConnected = false; Prefs.setRunning(this, false); super.onDestroy()
    }
}
