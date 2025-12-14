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
import com.wyoming.satellite.AudioConstants

class WyomingService : Service() {
    private var lastNonSilenceTime = 0L
    private var lastWakeWordTime = 0L
    
    private val TAG = "WyomingService"
    private val CHANNEL_ID = "wyoming_satellite_channel"
    private val NOTIFICATION_ID = 1
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    
    private var wyomingClient: WyomingClient? = null
    @Volatile private var isStreamingToServer = false
    private var audioProcessor: AudioProcessor? = null
    private var wakeWordDetector: WakeWordDetector? = null
    // Debug helper
    private var debugAudioRecorder: DebugAudioRecorder? = null

    // Audio buffer for not-yet-processed data (10 seconds max)
    private val notProcessingAudioData = ArrayDeque<ShortArray>()
    private val audioBufferLock = Object()
    private var audioProcessingThread: Thread? = null
    @Volatile private var audioProcessingThreadRunning = false
    
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
                    // Just buffer audio chunk
                    handleAudioChunk(audioData)
                }

                // Initialize debug helper together with other components
                audioProcessor?.let { debugAudioRecorder = DebugAudioRecorder(audioProcessor = it) }
                
                // Initialize Wyoming client
                // TODO: replace with real serverAddress/serverPort
                wyomingClient = WyomingClient() { event ->
                    // Handle Wyoming events
                    // handleWyomingEvent(event)
                }
                // Connect to Wyoming server (if needed)
                // serviceScope.launch { wyomingClient?.connect() }
                
                // Start audio capture
                audioProcessor?.startRecording()

                // Start audio processing thread
                startAudioProcessingThread()
                
                Log.d(TAG, "All components initialized successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing components", e)
            }
        }
    }
    
    private fun handleAudioChunk(audioData: ShortArray) {
        if (!isRunning) return

        // Simple RMS-based silence/noise filter
        val rms = calculateRMS(audioData)
        val now = System.currentTimeMillis()
        if (rms > AudioConstants.RMS_SILENCE_THRESHOLD) {
            lastNonSilenceTime = now
        } else {
            if (now - lastNonSilenceTime > AudioConstants.SILENCE_SKIP_DURATION_MS) {
                // Якщо йде стрімінг — зупиняємо
                if (isStreamingToServer) {
                    isStreamingToServer = false
                    Log.i(TAG, "⏹️ Stop streaming to server (silence timeout, handleAudioChunk)")
                }
                return
            }
        }

        synchronized(audioBufferLock) {
            // Keep buffer up to 10 seconds (SAMPLE_RATE * 10 / chunkSize)
            val maxChunks = (AudioConstants.SAMPLE_RATE * 10) / audioData.size
            if (notProcessingAudioData.size >= maxChunks) {
                notProcessingAudioData.removeFirst()
            }
            notProcessingAudioData.addLast(audioData)
        }
    }

    // RMS calculation for silence/noise filter
    private fun calculateRMS(audioChunk: ShortArray): Float {
        var sum = 0.0
        for (sample in audioChunk) {
            val normalized = sample.toDouble() / AudioConstants.PCM16_MAX
            sum += normalized * normalized
        }
        return kotlin.math.sqrt(sum / audioChunk.size).toFloat()
    }

    private fun startAudioProcessingThread() {
        audioProcessingThreadRunning = true
        audioProcessingThread = Thread {
        while (audioProcessingThreadRunning) {
            val audioChunks: List<ShortArray>
            synchronized(audioBufferLock) {
                audioChunks = notProcessingAudioData.toList()
                notProcessingAudioData.clear()
            }
            if (audioChunks.isEmpty()) {
                Thread.sleep(30)
                continue
            }
            val now = System.currentTimeMillis()
            for (audioData in audioChunks) {
                try {
                    debugAudioRecorder?.addAudio(audioData)
                    if (!isStreamingToServer){
                        val score = wakeWordDetector?.detectWakeWord(audioData)
                        if (score != null && score > 0.05f) {
                            Log.i(TAG, "🎤 Wake word detected with score: %.5f".format(score))
                            wyomingClient?.sendWakeWordDetected()
                            isStreamingToServer = true
                            lastWakeWordTime = now
                        }
                    }

                    if (isStreamingToServer) {
                        wyomingClient?.sendAudioChunk(audioData)
                        // Stop streaming if silence timeout
                        if (now - lastWakeWordTime > AudioConstants.STREAMING_TO_SERVER_TIMEOUT_MS) {
                            isStreamingToServer = false
                            Log.i(TAG, "⏹️ Stop streaming to server (silence timeout, processing thread)")
                        }
                        continue
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing audio chunk in thread", e)
                }
            }
        }
        }
        audioProcessingThread?.start()
    }

    private fun stopAudioProcessingThread() {
        audioProcessingThreadRunning = false
        audioProcessingThread?.interrupt()
        audioProcessingThread = null
    }
    
    // private fun handleWyomingEvent(event: String) {
    //     Log.d(TAG, "Wyoming event: $event")
    //     
    //     // Parse and handle different Wyoming protocol events
    //     // This will include TTS playback, ASR results, etc.
    // }
    
    override fun onDestroy() {
        // 1. Stop audio streaming first
        audioProcessor?.stopRecording()

        // 2. Stop audio processing thread
        stopAudioProcessingThread()

        // 3. (Optional) Wait for audio callbacks to finish (if needed, add delay or callback sync here)
        // For now, assume stopRecording is synchronous or callbacks are drained

        // 4. Clean up detector and other resources
        wakeWordDetector?.cleanup()
        // wyomingClient?.disconnect()

        // 5. Mark stopped and notify UI
        isRunning = false
        try {
            sendBroadcast(Intent("com.wyoming.satellite.action.SERVICE_STOPPED"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast service stopped", e)
        }

        // 6. Cancel coroutines and job
        serviceScope.launch {
            // Cancel all coroutines
        }
        try { serviceJob.cancel() } catch (_: Exception) {}

        Log.d(TAG, "Service destroyed")
        super.onDestroy()
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
