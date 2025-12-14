package com.wyoming.satellite

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class WyomingClient(
    private val eventCallback: (String) -> Unit
) {
    private val TAG = "WyomingClient"
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isConnected = false
    private val serverAddress = "192.168.0.100" // TODO: get from config or discovery
    private val serverPort = 10700 // TODO: get from config or discovery
    
    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to $serverAddress:$serverPort")
            socket = Socket(serverAddress, serverPort)
            writer = PrintWriter(socket!!.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            isConnected = true
            Log.d(TAG, "Connected to Wyoming server")
            // Send describe event
            sendDescribe()
            // Start listening for events
            startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to Wyoming server", e)
            isConnected = false
        }
    }
    
    private suspend fun startListening() = withContext(Dispatchers.IO) {
        try {
            while (isConnected) {
                val line = reader?.readLine()
                if (line != null) {
                    handleMessage(line)
                } else {
                    // Connection closed
                    Log.w(TAG, "Connection closed by server")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from socket", e)
        } finally {
            isConnected = false
        }
    }
    
    private fun handleMessage(message: String) {
        try {
            Log.d(TAG, "Received: $message")
            val json = JSONObject(message)
            val type = json.optString("type", "")
            
            when (type) {
                "info" -> handleInfo(json)
                "describe" -> handleDescribe(json)
                "audio-start" -> handleAudioStart(json)
                "audio-stop" -> handleAudioStop(json)
                "transcript" -> handleTranscript(json)
                "synthesize" -> handleSynthesize(json)
                "audio-chunk" -> handleAudioChunk(json)
                else -> Log.w(TAG, "Unknown message type: $type")
            }
            
            eventCallback(message)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }
    
    private fun handleInfo(json: JSONObject) {
        // Server info
        val name = json.optJSONObject("data")?.optString("name", "")
        val version = json.optJSONObject("data")?.optString("version", "")
        Log.d(TAG, "Server info - Name: $name, Version: $version")
    }
    
    private fun handleDescribe(json: JSONObject) {
        // Server capabilities
        Log.d(TAG, "Server capabilities: $json")
    }
    
    private fun handleAudioStart(json: JSONObject) {
        // Server ready to receive audio
        Log.d(TAG, "Audio start")
    }
    
    private fun handleAudioStop(json: JSONObject) {
        // Server finished processing audio
        Log.d(TAG, "Audio stop")
    }
    
    private fun handleTranscript(json: JSONObject) {
        // Speech recognition result
        val text = json.optJSONObject("data")?.optString("text", "")
        Log.d(TAG, "Transcript: $text")
    }
    
    private fun handleSynthesize(json: JSONObject) {
        // TTS request
        val text = json.optJSONObject("data")?.optString("text", "")
        Log.d(TAG, "Synthesize: $text")
    }
    
    private fun handleAudioChunk(json: JSONObject) {
        // TTS audio chunk
        Log.d(TAG, "Audio chunk received")
    }
    
    private fun sendMessage(message: JSONObject) {
        try {
            val messageStr = message.toString()
            Log.d(TAG, "Sending: $messageStr")
            writer?.println(messageStr)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }
    
    private fun sendDescribe() {
        val message = JSONObject().apply {
            put("type", "describe")
        }
        sendMessage(message)
    }
    
    fun sendWakeWordDetected() {
        val message = JSONObject().apply {
            put("type", "wake-word-detected")
            put("data", JSONObject().apply {
                put("name", "ok_nabu")
                put("timestamp", System.currentTimeMillis())
            })
        }
        sendMessage(message)
    }
    
    fun sendAudioChunk(audioData: ShortArray) {
        // Send audio chunk to server
        // This will be implemented with proper binary encoding
        Log.d(TAG, "Sending audio chunk: ${audioData.size} bytes")
    }
    
    fun disconnect() {
        try {
            isConnected = false
            writer?.close()
            reader?.close()
            socket?.close()
            Log.d(TAG, "Disconnected from Wyoming server")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }
}
