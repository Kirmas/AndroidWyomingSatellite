package com.wyoming.satellite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            (action == Intent.ACTION_PACKAGE_REPLACED && intent.data?.schemeSpecificPart == context.packageName)) {
            Log.d(TAG, "System event ($action) — checking permissions to auto-start service")

            val recordPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            val notifPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            if (recordPermission == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                notifPermission == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    val svcIntent = Intent(context, WyomingService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svcIntent)
                    } else {
                        context.startService(svcIntent)
                    }
                    Log.i(TAG, "WyomingService auto-started after $action")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on $action", e)
                }
            } else {
                Log.i(TAG, "Required permissions not granted — not starting service")
            }
        }
    }
}
