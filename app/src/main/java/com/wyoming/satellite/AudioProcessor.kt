package com.wyoming.satellite

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioProcessor(
    private val context: Context,
    private val audioCallback: (ShortArray) -> Unit
) {
    private val TAG = "AudioProcessor"
    
    // Audio configuration matching Wyoming protocol requirements
    private val SAMPLE_RATE = 16000 // 16kHz
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val CHUNK_SIZE = 1280 // 80ms at 16kHz (matches openWakeWord requirements)
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private val playbackLock = Any()
    private var playbackThread: Thread? = null
    
    fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }
        
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * 2 // Double buffer for safety
            
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }
            
            audioRecord?.startRecording()
            isRecording = true
            
            Log.d(TAG, "Audio recording started - Sample rate: $SAMPLE_RATE, Chunk size: $CHUNK_SIZE")
            
            // Start audio capture loop
            startAudioCaptureLoop()
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for audio recording", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio recording", e)
        }
    }
    
    private fun startAudioCaptureLoop() {
        Thread {
            val buffer = ShortArray(CHUNK_SIZE)
            
            while (isRecording) {
                val readResult = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: 0
                
                if (readResult > 0) {
                    // Pass audio chunk to callback
                    audioCallback(buffer.copyOf(readResult))
                } else {
                    Log.w(TAG, "Audio read error: $readResult")
                }
            }
        }.start()
    }
    
    fun stopRecording() {
        if (!isRecording) {
            return
        }
        
        isRecording = false
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "Audio recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording", e)
        }
    }
    
    /**
     * Play audio asynchronously. This method returns immediately and playback
     * runs on a dedicated thread managed by AudioProcessor. If a previous
     * playback thread is active, it will be interrupted before starting the
     * new one.
     */
    fun playAudio(audioData: ShortArray) {
        synchronized(playbackLock) {
            // Interrupt previous playback if still running; playback thread will
            // perform cleanup of its AudioTrack in finally block.
            playbackThread?.let {
                if (it.isAlive) {
                    try { it.interrupt() } catch (_: Exception) {}
                }
            }

            playbackThread = Thread {
                try {
                    val bufferSize = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AUDIO_FORMAT
                    )

                    audioTrack = AudioTrack.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AUDIO_FORMAT)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()

                    audioTrack?.play()
                    audioTrack?.write(audioData, 0, audioData.size)

                    // Sleep to allow playback to finish (approximate)
                    try {
                        Thread.sleep((audioData.size * 1000L / SAMPLE_RATE))
                    } catch (_: InterruptedException) {
                        // If interrupted while sleeping, just proceed to stop/cleanup
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing audio", e)
                } finally {
                    try {
                        audioTrack?.stop()
                    } catch (_: Exception) {}
                    try {
                        audioTrack?.release()
                    } catch (_: Exception) {}
                    audioTrack = null
                }
            }

            playbackThread?.start()
        }
    }
    
    fun cleanup() {
        stopRecording()
        // Stop any ongoing playback thread
        synchronized(playbackLock) {
            playbackThread?.let {
                try { it.interrupt() } catch (_: Exception) {}
            }
            playbackThread = null
        }
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }
}
