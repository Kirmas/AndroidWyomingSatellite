package com.wyoming.satellite

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DebugActivity : AppCompatActivity() {

    private lateinit var startDebugButton: Button
    private lateinit var playDebugButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        startDebugButton = findViewById(R.id.startDebugButton)
        playDebugButton = findViewById(R.id.playDebugButton)

        startDebugButton.setOnClickListener {
            val intent = Intent(this, WyomingService::class.java).apply { action = WyomingService.ACTION_START_DEBUG_RECORD }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Debug recording started", Toast.LENGTH_SHORT).show()
        }

        playDebugButton.setOnClickListener {
            val intent = Intent(this, WyomingService::class.java).apply { action = WyomingService.ACTION_PLAY_DEBUG }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }
}
