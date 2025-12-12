package com.wyoming.satellite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private lateinit var startButton: Button
    private lateinit var startDebugButton: Button
    private lateinit var playDebugButton: Button
    private lateinit var statusText: TextView
    
    private val PERMISSION_REQUEST_CODE = 100
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        Log.d(TAG, "onCreate called")
        
        // Initialize views
        startButton = findViewById(R.id.startButton)
        startDebugButton = findViewById(R.id.startDebugButton)
        playDebugButton = findViewById(R.id.playDebugButton)
        statusText = findViewById(R.id.statusText)
        
        // Set button listeners
        startButton.setOnClickListener {
            Log.d(TAG, "Start button clicked")
            if (checkPermissions()) {
                startService()
            } else {
                requestPermissions()
            }
        }

        startDebugButton.setOnClickListener {
            Log.d(TAG, "Start debug record clicked")
            val intent = Intent(this, WyomingService::class.java).apply {
                action = WyomingService.ACTION_START_DEBUG_RECORD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Debug recording started", Toast.LENGTH_SHORT).show()
        }

        playDebugButton.setOnClickListener {
            Log.d(TAG, "Play debug clicked")
            val intent = Intent(this, WyomingService::class.java).apply { action = WyomingService.ACTION_PLAY_DEBUG }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
        
        updateUI()
    }
    
    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startService()
            } else {
                Toast.makeText(
                    this,
                    "Permissions required for Wyoming Satellite",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun startService() {
        Log.d(TAG, "Starting WyomingService")
        
        val prefs = getSharedPreferences("wyoming_settings", MODE_PRIVATE)
        val address = prefs.getString("server_address", getString(R.string.default_server)) ?: ""
        val port = prefs.getString("server_port", getString(R.string.default_port)) ?: "10700"
        
        val intent = Intent(this, WyomingService::class.java).apply {
            putExtra("server_address", address)
            putExtra("server_port", port.toIntOrNull() ?: 10700)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        updateUI()
        Log.i(TAG, "Wyoming Satellite started")
        Toast.makeText(this, "Wake Word Detection Started", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateUI() {
        statusText.text = "Wake Word Detection Active"
    }
}
