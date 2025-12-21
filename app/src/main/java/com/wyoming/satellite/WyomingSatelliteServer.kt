package com.wyoming.satellite

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * WyomingSatelliteServer — сервер Wyoming-протоколу для Home Assistant
 * Приймає підключення від HA, надсилає device_info, wake_word_detected, audio, події
 * Приймає команди (play_audio, pipeline, error тощо)
 */
class WyomingSatelliteServer(
    private val port: Int = 10700,
    private val eventCallback: (String) -> Unit,
    private val deviceId: String = "android_satellite_1",
    private val deviceName: String = "Android Satellite"
) {
    private val TAG = "WyomingSatelliteServer"
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var isRunning = false

    fun start() {
        if (isRunning) return
        job = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Wyoming Satellite server listening on port $port")
                isRunning = true
                while (isRunning) {
                    clientSocket = serverSocket!!.accept()
                    Log.i(TAG, "Client connected: ${clientSocket!!.inetAddress}")
                    writer = PrintWriter(OutputStreamWriter(clientSocket!!.getOutputStream()), true)
                    reader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
                    listenLoop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
                isRunning = false
            }
        }
    }

    private suspend fun listenLoop() {
        try {
            var line: String? = null
            while (isRunning && reader?.readLine().also { line = it } != null) {
                line?.let { handleMessage(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read error", e)
        }
    }

    private fun handleMessage(message: String) {
        try {
            Log.d(TAG, "Received: $message")
            val json = JSONObject(message)
            when (json.optString("type")) {
                "command" -> handleCommand(json)
                "ping" -> sendPong()
                "audio" -> handlePlayAudio(json)
                "describe" -> sendDescribe()
                else -> Log.w(TAG, "Unknown message type: ${json.optString("type")}")
            }
            eventCallback(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    fun sendDeviceInfo() {
        val msg = JSONObject().apply {
            put("type", "info")
            put("device_id", deviceId)
            put("name", deviceName)
            put("capabilities", listOf("wake-word", "audio output"))
        }
        sendMessage(msg)
    }

    fun sendWakeWordDetected(wakeWordId: String) {
        val msg = JSONObject().apply {
            put("type", "event")
            put("event", "wake_word_detected")
            put("timestamp", System.currentTimeMillis())
            put("wake_word_id", wakeWordId)
            put("device_id", deviceId)
        }
        sendMessage(msg)
    }

    fun sendAudioFrame(audioData: ShortArray, rate: Int = 16000, width: Int = 2, channels: Int = 1) {
        val byteBuffer = ByteArray(audioData.size * 2)
        for (i in audioData.indices) {
            byteBuffer[i * 2] = (audioData[i].toInt() and 0xFF).toByte()
            byteBuffer[i * 2 + 1] = ((audioData[i].toInt() shr 8) and 0xFF).toByte()
        }
        val dataB64 = android.util.Base64.encodeToString(byteBuffer, android.util.Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("type", "audio")
            put("codec", "pcm")
            put("rate", rate)
            put("width", width)
            put("channels", channels)
            put("data", dataB64)
        }
        sendMessage(msg)
    }

    fun sendEndOfAudio() {
        val msg = JSONObject().apply {
            put("type", "event")
            put("event", "end_of_audio")
        }
        sendMessage(msg)
    }

    fun sendError(message: String) {
        val msg = JSONObject().apply {
            put("type", "event")
            put("event", "error")
            put("message", message)
        }
        sendMessage(msg)
    }

    private fun handleCommand(json: JSONObject) {
        val command = json.optString("command", "")
        when (command) {
            "start_pipeline" -> {
                val pipelineId = json.optString("pipeline_id", "")
                val deviceId = json.optString("device_id", "")
                Log.i(TAG, "Start pipeline: $pipelineId for $deviceId")
                // TODO: Start listening for user command (no wake word)
            }
            "stop_listening" -> {
                Log.i(TAG, "Stop listening command received")
                // TODO: Stop audio capture
            }
            "set_volume" -> {
                val volume = json.optDouble("volume", 1.0)
                Log.i(TAG, "Set volume: $volume")
                // TODO: Set device volume
            }
            else -> Log.w(TAG, "Unknown command: $command")
        }
    }

    private fun handlePlayAudio(json: JSONObject) {
        val dataB64 = json.optString("data", "")
        val rate = json.optInt("rate", 16000)
        val channels = json.optInt("channels", 1)
        if (dataB64.isNotEmpty()) {
            val audioBytes = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)
            // TODO: Play audioBytes using Android AudioTrack
            Log.i(TAG, "Playing audio: ${audioBytes.size} bytes, $rate Hz, $channels ch")
        }
    }

    private fun sendDescribe() {
        val infoData = JSONObject().apply {
            put("asr", emptyList<String>())
            put("tts", emptyList<String>())
            put("handle", emptyList<String>())
            put("intent", emptyList<String>())
            put("wake", emptyList<String>())
            put("satellite", JSONObject().apply {
                put("name", deviceName)
                put("attribution", JSONObject().apply {
                    put("name", "")
                    put("url", "")
                })
                put("installed", true)
                put("description", deviceName)
                put("version", "1.0")
                put("area", JSONObject.NULL)
                put("snd_format", JSONObject().apply {
                    put("channels", 1)
                    put("rate", 16000)
                    put("width", 2)
                })
            })
        }
        val header = JSONObject().apply {
            put("type", "info")
            put("version", "1.0.0")
            put("data", infoData)
        }
        sendMessage(header)
    }

    private fun sendPong() {
        val pong = JSONObject().apply { put("type", "pong") }
        sendMessage(pong)
    }

    private fun sendMessage(msg: JSONObject) {
        try {
            val str = msg.toString() + "\n"
            Log.d(TAG, "Send: $str")
            val bytes = str.toByteArray(Charsets.UTF_8)
            clientSocket?.getOutputStream()?.write(bytes)
            clientSocket?.getOutputStream()?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }

    fun stop() {
        isRunning = false
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        job?.cancel()
        Log.i(TAG, "Wyoming Satellite server stopped")
    }
}