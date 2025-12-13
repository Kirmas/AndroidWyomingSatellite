package com.wyoming.satellite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WyomingService : Service() {
    
    private val TAG = "WyomingService"
    private val CHANNEL_ID = "wyoming_satellite_channel"
    private val NOTIFICATION_ID = 1
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    
    // private var wyomingClient: WyomingClient? = null
    private var audioProcessor: AudioProcessor? = null
    private var wakeWordDetector: WakeWordDetector? = null
    // Debug helper
    private var debugAudioRecorder: DebugAudioRecorder? = null
    
    // private var serverAddress: String = ""
    // private var serverPort: Int = 10700
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        isRunning = true
        // notify UI that the service has started
        try {
            sendBroadcast(Intent("com.wyoming.satellite.action.SERVICE_STARTED"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast service started", e)
        }
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        // Handle debug actions if provided
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_DEBUG_RECORD -> {
                    debugAudioRecorder?.start()
                    return START_STICKY
                }
                ACTION_PLAY_DEBUG -> {
                    // Play current buffer without changing recording state
                    debugAudioRecorder?.play()
                    return START_STICKY
                }
                else -> {
                    // ignore other actions
                }
            }
        }
        
        // Get server configuration from intent
        // intent?.let {
        //     serverAddress = it.getStringExtra("server_address") ?: ""
        //     serverPort = it.getIntExtra("server_port", 10700)
        // }
        
        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Initialize components
        initializeComponents()
        
        return START_STICKY
    }
    
    private fun initializeComponents() {
        serviceScope.launch {
            try {
                // Initialize wake word detector
                wakeWordDetector = WakeWordDetector(applicationContext)
                wakeWordDetector?.initialize()
                
                // Initialize audio processor
                audioProcessor = AudioProcessor(applicationContext) { audioData ->
                    // Process audio chunk
                    handleAudioChunk(audioData)
                }

                // Initialize debug helper together with other components
                audioProcessor?.let { debugAudioRecorder = DebugAudioRecorder(audioProcessor = it) }
                
                // Initialize Wyoming client
                // wyomingClient = WyomingClient(serverAddress, serverPort) { event ->
                //     // Handle Wyoming events
                //     handleWyomingEvent(event)
                // }
                
                // Connect to Wyoming server
                // wyomingClient?.connect()
                
                // Start audio capture
                audioProcessor?.startRecording()
                
                Log.d(TAG, "All components initialized successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing components", e)
            }
        }
    }
    
    private fun handleAudioChunk(audioData: ShortArray) {
        serviceScope.launch {
            try {
                // Append to debug buffer (debug helper handles whether it's recording)
                debugAudioRecorder?.addAudio(audioData)
                // Check for wake word (returns score as Float?)
                val score = wakeWordDetector?.detectWakeWord(audioData)
                
                // Score > 0.05 means wake word detected
                if (score != null && score > 0.05f) {
                    Log.i(TAG, "🎤 Wake word detected with score: %.5f".format(score))
                    // wyomingClient?.sendWakeWordDetected()
                }
                
                // Send audio to server (when actively listening)
                // This will be controlled by Wyoming protocol state machine
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio chunk", e)
            }
        }
    }
    
    // private fun handleWyomingEvent(event: String) {
    //     Log.d(TAG, "Wyoming event: $event")
    //     
    //     // Parse and handle different Wyoming protocol events
    //     // This will include TTS playback, ASR results, etc.
    // }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        // mark stopped and notify UI
        isRunning = false
        try {
            sendBroadcast(Intent("com.wyoming.satellite.action.SERVICE_STOPPED"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast service stopped", e)
        }

        // Clean up resources
        audioProcessor?.stopRecording()
        // wyomingClient?.disconnect()
        wakeWordDetector?.cleanup()
        
        serviceScope.launch {
            // Cancel all coroutines
        }
        // Cancel job to avoid leaks
        try { serviceJob.cancel() } catch (_: Exception) {}
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wyoming Satellite Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Wyoming voice satellite background service"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wyoming Satellite")
            .setContentText("Listening for wake word...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        @Volatile var isRunning: Boolean = false
        const val ACTION_START_DEBUG_RECORD = "com.wyoming.satellite.action.START_DEBUG_RECORD"
        const val ACTION_PLAY_DEBUG = "com.wyoming.satellite.action.PLAY_DEBUG"
        const val ACTION_SERVICE_STARTED = "com.wyoming.satellite.action.SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED = "com.wyoming.satellite.action.SERVICE_STOPPED"
    }
}
