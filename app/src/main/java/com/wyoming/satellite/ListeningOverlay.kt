package com.wyoming.satellite

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class ListeningOverlay(private val context: Context) {
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun show() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { showInternal() }
        } else {
            showInternal()
        }
    }

    private fun showInternal() {
        if (overlayView != null) return
        if (windowManager == null) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        // Wake up screen if off
        @Suppress("DEPRECATION")
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "WyomingService:WakeWordOverlay"
            )
            wakeLock?.acquire(3000)
        }
        val textView = TextView(context).apply {
            text = "Listening..."
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            textSize = 32f
            setPadding(60, 40, 60, 40)
            gravity = Gravity.CENTER
        }
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        overlayView = textView
        windowManager?.addView(textView, params)
    }

    fun hide() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { hideInternal() }
        } else {
            hideInternal()
        }
    }

    private fun hideInternal() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }
}
