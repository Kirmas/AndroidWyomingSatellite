package com.wyoming.satellite

object AudioConstants {
    const val SAMPLE_RATE = 16000
    const val PCM16_MAX = 32768.0
    const val RMS_SILENCE_THRESHOLD = 0.01f
    const val SILENCE_SKIP_DURATION_MS = 3000L // 3 секунди, легко твікати
}
