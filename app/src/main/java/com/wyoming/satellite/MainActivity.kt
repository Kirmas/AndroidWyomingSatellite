package com.wyoming.satellite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
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
    
    private lateinit var startStopButton: Button
    private lateinit var statusText: TextView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            // Update UI when service starts/stops
            updateUI()
            updateNavDebugState()
        }
    }
    
    private val PERMISSION_REQUEST_CODE = 100
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        Log.d(TAG, "onCreate called")
        
        // Initialize views
        startStopButton = findViewById(R.id.startStopButton)
        statusText = findViewById(R.id.statusText)

        // Drawer and toolbar
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.navigationView)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.nav_open,
            R.string.nav_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_debug -> startActivity(Intent(this, DebugActivity::class.java))
                R.id.nav_config -> startActivity(Intent(this, ConfigActivity::class.java))
            }
            drawerLayout.closeDrawers()
            true
        }

        // Set button listener
        startStopButton.setOnClickListener {
            if (WyomingService.isRunning) {
                Log.d(TAG, "Stop button clicked")
                stopWyomingService()
            } else {
                Log.d(TAG, "Start button clicked")
                if (checkPermissions()) {
                    startWyomingService()
                } else {
                    requestPermissions()
                }
            }
        }

        updateUI()
    }
    
    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
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
            Manifest.permission.RECORD_AUDIO
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
                startWyomingService()
            } else {
                Toast.makeText(
                    this,
                    "Permissions required for Wyoming Satellite",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    private fun startWyomingService() {
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
        // optimistically update UI; service will broadcast actual state
        WyomingService.isRunning = true
        updateUI()
        Log.i(TAG, "Wyoming Satellite started")
        Toast.makeText(this, "Wake Word Detection Started", Toast.LENGTH_SHORT).show()
    }

    private fun stopWyomingService() {
        Log.d(TAG, "Stopping WyomingService")
        val intent = Intent(this, WyomingService::class.java)
        try {
            stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
        }
        WyomingService.isRunning = false
        updateUI()
        Toast.makeText(this, "Wyoming Service Stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateUI() {
        if (WyomingService.isRunning) {
            statusText.text = getString(R.string.status_running)
            startStopButton.text = getString(R.string.stop_service)
        } else {
            statusText.text = getString(R.string.status_stopped)
            startStopButton.text = getString(R.string.start_service)
        }
        updateNavDebugState()
    }

    private fun updateNavDebugState() {
        try {
            val item = navigationView.menu.findItem(R.id.nav_debug)
            item.isEnabled = WyomingService.isRunning
        } catch (_: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(WyomingService.ACTION_SERVICE_STARTED)
            addAction(WyomingService.ACTION_SERVICE_STOPPED)
        }
        registerReceiver(serviceStateReceiver, filter)
        // ensure nav state matches current service state
        updateNavDebugState()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(serviceStateReceiver) } catch (_: Exception) {}
    }

    // Navigation handled by NavigationView (drawer)
}
