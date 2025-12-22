package com.wyoming.satellite

import org.json.JSONObject

interface WyomingServerListener {
    fun onStartStreaming()
    fun onStopStreaming()
    
    fun onAudioStart(rate: Int, width: Int, channels: Int)
    fun onAudioChunk(payload: ByteArray)
    fun onAudioStop()
}