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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.wyoming.satellite.AudioConstants
import com.wyoming.satellite.WyomingSatelliteServer
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.SampleRate as WebRTCSampleRate
import com.konovalov.vad.webrtc.config.FrameSize as WebRTCFrameSize
import com.konovalov.vad.webrtc.config.Mode as WebRTCMode
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.SampleRate as SileroSampleRate
import com.konovalov.vad.silero.config.FrameSize as SileroFrameSize
import com.konovalov.vad.silero.config.Mode as SileroMode

class WyomingService : Service(), WyomingServerListener {
    private var listeningOverlay: ListeningOverlay? = null
    private var lastWakeWordTime = 0L
    
    private val TAG = "WyomingService"
    private val CHANNEL_ID = "wyoming_satellite_channel"
    private val NOTIFICATION_ID = 1
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    
    private var wyomingServer: WyomingSatelliteServer? = null
    private val prefs by lazy { applicationContext.getSharedPreferences("wyoming_settings", MODE_PRIVATE) }
    private val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: run {
            val id = Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
            val value = "android_${id ?: System.currentTimeMillis()}"
            prefs.edit().putString("device_id", value).apply()
            value
        }
    }
    private val deviceName: String by lazy {
        prefs.getString("device_name", null) ?: run {
            val name = "Android ${android.os.Build.MODEL ?: "Satellite"}"
            prefs.edit().putString("device_name", name).apply()
            name
        }
    }
    @Volatile private var isStreamingToServer = false
    private var audioRecorder: WyomingAudioRecorder? = null
    private var audioPlayer: WyomingAudioPlayer? = null
    private var wakeWordDetector: WakeWordDetector? = null
    // VAD
    private var vadWebRTC: VadWebRTC? = null
    private var vadWebRTCTailChunk: ShortArray? = null
    private var vadSilero: VadSilero? = null
    private var vadSileroTailChunk: ShortArray? = null
    // Debug helper
    private var debugAudioRecorder: DebugAudioRecorder? = null

    // Audio buffer for not-yet-processed data (10 seconds max)
    private val notProcessingAudioData = ArrayDeque<ShortArray>()
    private val audioBufferLock = Object()
    private var audioProcessingThread: Thread? = null
    @Volatile private var audioProcessingThreadRunning = false

    // For overlap: store last half-chunk
    private var lastHalfChunk: ShortArray? = null
    
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
                
                vadWebRTC = VadWebRTC(
                    sampleRate = WebRTCSampleRate.SAMPLE_RATE_16K,
                    frameSize = WebRTCFrameSize.FRAME_SIZE_320,
                    mode = WebRTCMode.VERY_AGGRESSIVE,
                    silenceDurationMs = 300,
                    speechDurationMs = 50
                )

                vadSilero = VadSilero(
                    applicationContext,
                    sampleRate = SileroSampleRate.SAMPLE_RATE_16K,
                    frameSize = SileroFrameSize.FRAME_SIZE_512,
                    mode = SileroMode.NORMAL,
                    silenceDurationMs = 300,
                    speechDurationMs = 50
                )

                // Initialize audio recorder
                audioRecorder = WyomingAudioRecorder() { audioData ->
                    // Just buffer audio chunk
                    handleAudioChunk(audioData)
                }

                audioPlayer = WyomingAudioPlayer()

                // Initialize debug helper together with other components
                audioPlayer?.let { debugAudioRecorder = DebugAudioRecorder(audioPlayer = it) }
                
                // Initialize Wyoming Satellite server
                wyomingServer = WyomingSatelliteServer(
                    context = applicationContext,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    port = 10700,
                    serverListener = this@WyomingService
                )
                wyomingServer?.start()
                
                // Start audio capture
                audioRecorder?.start()

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
        
        if (!isVadSpeechChunk(audioData, vadType = "webrtc")) {
            // Якщо йде стрімінг — зупиняємо
            if (isStreamingToServer) {
                isStreamingToServer = false
                listeningOverlay?.hide()
                listeningOverlay = null
                Log.i(TAG, "⏹️ Stop streaming to server (silence timeout, handleAudioChunk)")
            }
            return
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

    private fun isVadSpeechChunk(audioData: ShortArray, vadType: String = "webrtc"): Boolean {
        val startTime = System.currentTimeMillis()
        var retValue = false

        if (vadType == "webrtc") {
            vadWebRTC?.let { vad ->
                var combinedChunk: ShortArray?
                if  (vadWebRTCTailChunk != null) {
                    combinedChunk = ShortArray(vadWebRTCTailChunk!!.size + audioData.size)
                    System.arraycopy(vadWebRTCTailChunk!!, 0, combinedChunk, 0, vadWebRTCTailChunk!!.size)
                    System.arraycopy(audioData, 0, combinedChunk, vadWebRTCTailChunk!!.size, audioData.size)
                } else {
                    combinedChunk = audioData
                }
                val numChunks = combinedChunk.size / vad.frameSize.value
                for (i in 0 until numChunks) {
                    val start = i * vad.frameSize.value
                    val end = start + vad.frameSize.value
                    val subChunk = combinedChunk.copyOfRange(start, end)
                    if (vad.isSpeech(subChunk)) {
                        retValue = true
                    }
                }
                // Save tail chunk for next call
                val remaining = combinedChunk.size % vad.frameSize.value
                if (remaining > 0) {
                    vadWebRTCTailChunk = combinedChunk.copyOfRange(combinedChunk.size - remaining, combinedChunk.size)
                } else {
                    vadWebRTCTailChunk = null
                }
            }
        }
        else if (vadType == "silero") {
            vadSilero?.let { vad ->
                var combinedChunk2: ShortArray?
                if  (vadSileroTailChunk != null) {
                    combinedChunk2 = ShortArray(vadSileroTailChunk!!.size + audioData.size)
                    System.arraycopy(vadSileroTailChunk!!, 0, combinedChunk2, 0, vadSileroTailChunk!!.size)
                    System.arraycopy(audioData, 0, combinedChunk2, vadSileroTailChunk!!.size, audioData.size)
                } else {
                    combinedChunk2 = audioData
                }
                val numChunks = combinedChunk2.size / vad.frameSize.value
                for (i in 0 until numChunks) {
                    val start = i * vad.frameSize.value
                    val end = start + vad.frameSize.value
                    val subChunk = combinedChunk2.copyOfRange(start, end)
                    if (vad.isSpeech(subChunk)) {
                        retValue = true
                    }
                }
                // Save tail chunk for next call
                val remaining2 = combinedChunk2.size % vad.frameSize.value
                if (remaining2 > 0) {
                    vadSileroTailChunk = combinedChunk2.copyOfRange(combinedChunk2.size - remaining2, combinedChunk2.size)
                } else {
                    vadSileroTailChunk = null
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        if(elapsed > 80) Log.d(TAG, "isVadSpeechChunk execution time: $elapsed ms (vadType=$vadType)")
        return retValue
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

                        if (!isStreamingToServer) {
                            val score = wakeWordDetector?.detectWakeWord(audioData)
                            if (score != null && score > 0.05f) {
                                Log.i(TAG, "🎤 Wake word detected with score: %.5f".format(score))
                                //wyomingServer?.sendWakeWordDetected("default")
                                isStreamingToServer = true
                                lastWakeWordTime = now
                                listeningOverlay = ListeningOverlay(this)
                                listeningOverlay?.show()
                            }
                        }

                        if (isStreamingToServer) {
                            //wyomingServer?.sendAudioFrame(audioData)
                            // Stop streaming if silence timeout
                            if (now - lastWakeWordTime > AudioConstants.STREAMING_TO_SERVER_TIMEOUT_MS) {
                                isStreamingToServer = false
                                Log.i(TAG, "⏹️ Stop streaming to server (silence timeout, processing thread)")
                                listeningOverlay?.hide()
                                listeningOverlay = null
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
        listeningOverlay?.hide()
        // 1. Stop audio streaming first
        audioRecorder?.stop()

        // 2. Stop audio processing thread
        stopAudioProcessingThread()

        // 3. (Optional) Wait for audio callbacks to finish (if needed, add delay or callback sync here)
        // For now, assume stopRecording is synchronous or callbacks are drained

        // 4. Clean up detector and other resources
        wakeWordDetector?.cleanup()
        wyomingServer?.stop()

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

    override fun onStartStreaming() { }

    override fun onStopStreaming() { }

    override fun onAudioStart(rate: Int, width: Int, channels: Int){
        audioPlayer?.setup(
            sampleRate = rate,
            channels = channels,
            width = width
        )

        audioRecorder?.stop()
    }

    override fun onAudioChunk(payload: ByteArray) { 
        audioPlayer?.playChunk(payload)
    }

    override fun onAudioStop() { 
        audioPlayer?.stopAndAwait()        
        wyomingServer?.sendAudioPlayed()        
        audioRecorder?.start()
    }

    companion object {
        @Volatile var isRunning: Boolean = false
        const val ACTION_START_DEBUG_RECORD = "com.wyoming.satellite.action.START_DEBUG_RECORD"
        const val ACTION_PLAY_DEBUG = "com.wyoming.satellite.action.PLAY_DEBUG"
        const val ACTION_SERVICE_STARTED = "com.wyoming.satellite.action.SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED = "com.wyoming.satellite.action.SERVICE_STOPPED"
    }
}
