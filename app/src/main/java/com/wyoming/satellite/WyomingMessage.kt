package com.wyoming.satellite

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import android.util.Log

data class WyomingMessage(
    val type: String,
    val metadata: JSONObject = JSONObject(), //for debugging save all metadata
    val data: JSONObject = JSONObject(),
    val payload: ByteArray? = null
) {
    override fun toString(): String {
        val metaStr = metadata.toString()
        val dataStr = if (data.length() > 0) data.toString() else "{}"
        val payloadSize = if (payload != null) "${payload.size} bytes" else "null"
        
        return "$metaStr$dataStr[$payloadSize]"
    }

    fun send(outputStream: OutputStream) {
        metadata.put("type", type)
        metadata.put("version", "1.0")

        val hasData = data.length() > 0
        val dataBytes = if (hasData) {
            data.toString().toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        
        if (dataBytes != null) {
            metadata.put("data_length", dataBytes.size)
        }

        if (payload != null && payload.isNotEmpty()) {
            metadata.put("payload_length", payload.size)
        }

        synchronized(outputStream) {
            outputStream.write((metadata.toString() + "\n").toByteArray(Charsets.UTF_8))
            if (dataBytes != null) {
                outputStream.write(dataBytes)
            }
            if (payload != null) {
                outputStream.write(payload)
            }
            
            outputStream.flush()
        }
    }

    companion object {
        private const val TAG = "WyomingMessage"
        
        fun readFromStream(stream: BufferedInputStream): WyomingMessage? {
            val jsonLine = readLine(stream)
            if (jsonLine.isEmpty()) return null

            try {
                val header = JSONObject(jsonLine)
                val type = header.optString("type")
                
                val dataLength = header.optInt("data_length", 0)
                var dataObj = JSONObject()
                
                if (dataLength > 0) {
                    val dataBuffer = ByteArray(dataLength)
                    readExactly(stream, dataBuffer)
                    val dataStr = String(dataBuffer, Charsets.UTF_8)
                    try {
                        dataObj = JSONObject(dataStr)
                    } catch (e: Exception) {
                        Log.w(TAG, "Data is not JSON: $dataStr")
                    }
                }

                val payloadLength = header.optInt("payload_length", 0)
                var payloadBytes: ByteArray? = null
                
                if (payloadLength > 0) {
                    payloadBytes = ByteArray(payloadLength)
                    readExactly(stream, payloadBytes)
                }

                return WyomingMessage(
                    type = type,
                    metadata = header,
                    data = dataObj,
                    payload = payloadBytes
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Wyoming message", e)
                return null
            }
        }
        
        private fun readExactly(stream: BufferedInputStream, buffer: ByteArray) {
            var totalRead = 0
            while (totalRead < buffer.size) {
                val count = stream.read(buffer, totalRead, buffer.size - totalRead)
                if (count == -1) throw java.io.IOException("Unexpected end of stream")
                totalRead += count
            }
        }

        private fun readLine(stream: BufferedInputStream): String {
            val buffer = ByteArrayOutputStream()
            var byteVal: Int
            while (stream.read().also { byteVal = it } != -1) {
                if (byteVal == '\n'.code) break
                buffer.write(byteVal)
            }
            return buffer.toString("UTF-8")
        }
    }
}
