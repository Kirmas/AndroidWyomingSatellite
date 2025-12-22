package com.wyoming.satellite

import android.util.Log
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WyomingSatelliteServer(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val port: Int = 10700,
    private val serverListener: WyomingServerListener
) {
    private val TAG = "WyomingSatelliteServer"
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val SERVICE_TYPE = "_wyoming._tcp"
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedInputStream? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var isRunning = false

    fun start() {
        if (isRunning) return
        
        registerService()
        job = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Wyoming Satellite server listening on port $port")
                isRunning = true
                while (isRunning) {
                    clientSocket = serverSocket!!.accept()
                    Log.i(TAG, "Client connected: ${clientSocket!!.inetAddress}")
                    listenLoop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
                isRunning = false
            } finally {
                stop()
            }
        }
    }
    
    private fun registerService() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            
            val serviceInfo = NsdServiceInfo()
            serviceInfo.serviceName = deviceName
            serviceInfo.serviceType = SERVICE_TYPE
            serviceInfo.port = port

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    Log.i(TAG, "NSD Service registered: ${NsdServiceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    Log.i(TAG, "NSD Service unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD Unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(
                serviceInfo, 
                NsdManager.PROTOCOL_DNS_SD, 
                registrationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    private fun unregisterService() {
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager?.unregisterService(registrationListener)
                Log.i(TAG, "NSD Unregister request sent")
            } catch (e: Exception) {
                // Часто падає, якщо сервіс вже зупинено, це нормально
                Log.w(TAG, "Error unregistering NSD service (might be already stopped): ${e.message}")
            } finally {
                registrationListener = null
            }
        }
    }

    private suspend fun listenLoop() {
try {
            val inputStream = BufferedInputStream(clientSocket!!.getInputStream())
            val outputStream = clientSocket!!.getOutputStream() // Беремо потік для запису

            while (isRunning) {
                // МАГІЯ ТУТ: Всього один рядок для читання
                val message = WyomingMessage.readFromStream(inputStream) ?: break
                
                // Обробка
                handleMessage(message, outputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Loop error", e)
        }
    }

    private fun handleMessage(msg: WyomingMessage, outputStream: OutputStream) {
        
        if (msg.type != "audio" && msg.type != "ping") {
            Log.d(TAG, "RX: ${msg}")
        }

       when (msg.type) {
            "describe" -> WyomingMessage(type = "info", metadata = JSONObject().apply { put("data", getDescribeJson()) }).send(outputStream)
            "run-satellite" -> Log.i(TAG, "Run-satellite command received not implemented")
            "pause-satellite" -> Log.i(TAG, "Pause-satellite command received not implemented")
            "detect" -> Log.i(TAG, "Detect command received not implemented must be local")
            "error" -> Log.e(TAG, "Error from server: ${msg.metadata}")
            "detection" -> Log.i(TAG, "Detection event received not implemented must be local")
            "transcribe" -> Log.i(TAG, "Transcribe command received not implemented")
            "voice-started" -> Log.i(TAG, "Voice-started event received not implemented")
            "voice-stopped" -> Log.i(TAG, "Voice-stopped event received not implemented")
            "audio-started" -> Log.i(TAG, "Audio-started event received not implemented")
            "audio-chunk" -> Log.i(TAG, "Audio-chunk event received not implemented")
            "audio-stop" -> Log.i(TAG, "Audio-stopped event received not implemented")
            "ping" -> WyomingMessage(type = "pong").send(outputStream)
            else -> Log.w(TAG, "Unknown message type: ${msg.type}")
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

    private fun getDescribeJson(): JSONObject {
        return JSONObject().apply {
            put("asr", JSONArray())
            put("tts", JSONArray())
            put("handle", JSONArray())
            put("intent", JSONArray())
            put("wake", JSONArray())
            
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
        unregisterService()
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        job?.cancel()
        Log.i(TAG, "Wyoming Satellite server stopped")
    }
}