package com.wyoming.satellite

import android.media.AudioFormat
import android.util.Log

class DebugAudioRecorder(
    private val sampleRate: Int = AudioConstants.SAMPLE_RATE,
    private val seconds: Int = 30,
    private val audioPlayer: WyomingAudioPlayer
) {
    private val TAG = "DebugAudioRecorder"
    private val bufferSize = sampleRate * seconds
    private val buffer = ShortArray(bufferSize)
    private var writeIndex = 0
    private var count = 0
    @Volatile private var recording = false

    val isRecording: Boolean
        get() = recording

    // Public simple API requested: start(), play(), addAudio()
    fun start() {
        synchronized(buffer) {
            writeIndex = 0
            count = 0
            recording = true
        }
        Log.i(TAG, "DebugAudioRecorder: started")
    }

    fun addAudio(data: ShortArray) {
        if (!recording) return
        var shouldPlay = false
        synchronized(buffer) {
            val remaining = bufferSize - writeIndex
            if (remaining <= 0) {
                // already full; drop incoming
            } else {
                val toCopy = kotlin.math.min(remaining, data.size)
                System.arraycopy(data, 0, buffer, writeIndex, toCopy)
                writeIndex += toCopy
                count = kotlin.math.min(bufferSize, count + toCopy)
                if (count == bufferSize) shouldPlay = true
            }
        }

        if (shouldPlay) {
            Log.i(TAG, "DebugAudioRecorder: buffer full — auto-playing snapshot")
            play()
        }
    }

    fun play() {
        // Stop recording before playback;
        recording = false

        val audio = snapshot()
        if (audio.isEmpty()) {
            Log.i(TAG, "DebugAudioRecorder: no audio to play")
            return
        }

        audioPlayer.setup(sampleRate) 
        audioPlayer.playRaw(audio)
    }

    fun snapshot(): ShortArray {
        synchronized(buffer) {
            val out = ShortArray(count)
            if (count > 0) {
                System.arraycopy(buffer, 0, out, 0, count)
                writeIndex = 0
                count = 0
            }
            return out
        }
    }
}
