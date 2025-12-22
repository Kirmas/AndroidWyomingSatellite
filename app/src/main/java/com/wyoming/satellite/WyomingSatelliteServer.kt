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
            val outputStream = clientSocket!!.getOutputStream()

            while (isRunning) {
                val message = WyomingMessage.readFromStream(inputStream) ?: break
                handleMessage(message, outputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Loop error", e)
        }
    }

    fun sendAudioPlayed() {
        val outputStream = clientSocket!!.getOutputStream()
        WyomingMessage(type = "played").send(outputStream)
    }

    private fun handleMessage(msg: WyomingMessage, outputStream: OutputStream) {
        
        if (msg.type != "audio-chunk" && msg.type != "ping") {
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
            "audio-start" -> {
                val rate = msg.data.optInt("rate", 16000)
                val width = msg.data.optInt("width", 2)
                val channels = msg.data.optInt("channels", 1)
                serverListener.onAudioStart(rate, width, channels)
            }
            "audio-chunk" -> {
                val payloadLength = msg.metadata.optInt("payload_length", 0)
                if (payloadLength > 0 && msg.payload != null && msg.payload.size == payloadLength) {
                    serverListener.onAudioChunk(msg.payload)
                } else {
                    Log.w(TAG, "Audio-chunk message with invalid payload")
                }
            }
            "audio-stop" -> serverListener.onAudioStop()
            "ping" -> WyomingMessage(type = "pong").send(outputStream)
            else -> Log.w(TAG, "Unknown message type: ${msg.type}")
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