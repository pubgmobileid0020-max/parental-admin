package com.example.admin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {

    companion object {
        const val EXTRA_SCREEN_RESULT = "screen_result_code"
        const val EXTRA_SCREEN_DATA = "screen_data"
        const val ACTION_STOP = "com.example.admin.STOP"

        @Volatile var instance: ConnectionService? = null

        fun push(ctx: Context, kind: String, data: String) {
            instance?.sendPushInternal(kind, data)
        }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backoff = 1000L
    private var shouldRun = false
    private var reconnectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(1001, buildNotification("Connecting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shouldRun = false
            reconnectJob?.cancel()
            try { ws?.close(1000, "stop") } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        val rc = intent?.getIntExtra(EXTRA_SCREEN_RESULT, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (rc != Int.MIN_VALUE) {
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>(EXTRA_SCREEN_DATA)
            if (data != null) {
                val i = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, rc)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, i)
            }
        }

        shouldRun = true
        Prefs.setRunning(this, true)
        connect()
        return START_STICKY
    }

    private fun connect() {
        if (!shouldRun) return
        val server = Prefs.getServer(this).trim()
        val psk = Prefs.getPsk(this).trim()
        if (server.isBlank() || psk.isBlank()) {
            updateNotification("Set URL and PSK")
            return
        }
        val wsUrl = buildWsUrl(server)
        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, listener)
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
            val hello = JSONObject()
                .put("role", "device")
                .put("psk", Prefs.getPsk(this@ConnectionService))
            webSocket.send(hello.toString())
            updateNotification("Connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "ready" -> updateNotification("Ready")
                        "error" -> updateNotification("Auth failed")
                        "cmd" -> {
                            val id = obj.optString("id")
                            val cmd = obj.optString("cmd")
                            val args = obj.optJSONObject("args") ?: JSONObject()
                            val res = CommandHandler.dispatch(this@ConnectionService, cmd, args)
                            val reply = JSONObject()
                                .put("type", "reply")
                                .put("id", id)
                                .put("ok", res.optBoolean("ok", false))
                            if (res.has("data")) reply.put("data", res.opt("data"))
                            if (res.has("error")) reply.put("error", res.optString("error"))
                            webSocket.send(reply.toString())
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            updateNotification("Reconnecting…")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (shouldRun) scheduleReconnect()
        }
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
        val payload = JSONObject()
            .put("type", "push")
            .put("kind", kind)
            .put("data", data)
            .toString()
        try { ws?.send(payload) } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel("conn", "Connection", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, "conn")
            .setContentTitle("Device Manager")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1001, buildNotification(text))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        shouldRun = false
        reconnectJob?.cancel()
        try { ws?.close(1000, "destroy") } catch (_: Exception) {}
        instance = null
        Prefs.setRunning(this, false)
        super.onDestroy()
    }
}
