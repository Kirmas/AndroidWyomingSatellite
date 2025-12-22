package com.wyoming.satellite

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class WyomingAudioRecorder(
    private val audioCallback: (ShortArray) -> Unit
) {
    private val TAG = "WyomingAudioRecorder"
    private val CHUNK_SIZE = 1280 // 80ms of audio at 16kHz
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    fun start() {
        if (isRecording) return
        
        val bufferSize = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Failed to init AudioRecord")
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        Thread {
            val buffer = ShortArray(CHUNK_SIZE)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: 0
                if (read > 0) {
                    audioCallback(buffer.copyOf(read))
                }
            }
        }.start()
        
        Log.d(TAG, "Recording started")
    }

    fun stop() {
        isRecording = false
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
    }
}