package com.wyoming.satellite

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*

class WyomingAudioPlayer {
    private val TAG = "WyomingAudioPlayer"
    private var audioTrack: AudioTrack? = null    
    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setup(sampleRate: Int, channels: Int = 1, width: Int = 2) {
        
        stop()

        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                Log.w(TAG, "Unsupported channel count $channels, defaulting to MONO")
                AudioFormat.CHANNEL_OUT_MONO
            }
        }

        val encoding = when (width) {
            1 -> AudioFormat.ENCODING_PCM_8BIT
            2 -> AudioFormat.ENCODING_PCM_16BIT
            else -> {
                Log.w(TAG, "Unsupported sample width $width, defaulting to 16BIT")
                AudioFormat.ENCODING_PCM_16BIT
            }
        }        
        
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        
        audioTrack = AudioTrack.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build())
            .setBufferSizeInBytes(bufferSize.coerceAtLeast(8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        Log.i(TAG, "AudioTrack setup: $sampleRate Hz, $channels channels, width $width")
    }

    fun playChunk(data: ByteArray) {
        audioTrack?.write(data, 0, data.size)
    }

    fun playRaw(data: ShortArray) {
        playerScope.launch {
            try {                
                val result = audioTrack?.write(data, 0, data.size)
                
                if (result == null || result < 0) {
                    Log.e(TAG, "Error writing to AudioTrack: $result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "playRaw failed", e)
            }
        }
    }

    fun interrupt() {
        try {
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Interrupt failed", e)
        }
    }

    fun stop() {
        playerScope.coroutineContext.cancelChildren()
        
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
        }
        audioTrack = null
    }
}