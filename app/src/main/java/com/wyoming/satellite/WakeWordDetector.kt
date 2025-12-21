package com.wyoming.satellite

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.*
import java.io.File
import com.wyoming.satellite.AudioConstants

/**
 * Wake Word Detector based on OpenWakeWord
 * Ported from: https://github.com/hasanatlodhi/OpenwakewordforAndroid
 * 
 * Uses three ONNX models in sequence:
 * 1. melspectrogram.onnx - converts audio to mel spectrogram
 * 2. embedding_model.onnx - generates embeddings from mel spectrogram
 * 3. selected wake-word model (e.g. hey_nabu.onnx) - classifies embeddings for wake word detection
 */
//https://github.com/dscripka/openWakeWord/blob/main/openwakeword/model.py#L213 - перевірити вейк ворд логіну бо у нас щось не так

class WakeWordDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val THRESHOLD = 0.05f
        private const val FEATURE_FRAMES_NEEDED = 16
        private const val N_SAMPLES_PER_FEATURE = 1280 // 80ms at AudioConstants.SAMPLE_RATE
    }
    
    // ONNX Runtime components
    private var ortEnvironment: OrtEnvironment? = null
    private var melSpectrogramSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var wakeWordSession: OrtSession? = null
    
    // Audio buffering (10 seconds max)
    private val rawDataBuffer = ArrayDeque<Float>(AudioConstants.SAMPLE_RATE * 10)
    private var rawDataRemainder = FloatArray(0)
    private var accumulatedSamples = 0
    
    // Mel spectrogram buffer [time_frames, 32_freq_bins]
    private var melSpectrogramBuffer = Array(76) { FloatArray(32) { 1.0f } }
    
    // Feature/embedding buffer [n_features, 96]
    private var featureBuffer: Array<FloatArray>? = null
    
    suspend fun initialize() {
        try {
            Log.i(TAG, "Initializing wake word detector...")
            
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            // Load models from assets
            melSpectrogramSession = ortEnvironment?.createSession(
                context.assets.open("models/melspectrogram.onnx").readBytes()
            )
            
            embeddingSession = ortEnvironment?.createSession(
                context.assets.open("models/embedding_model.onnx").readBytes()
            )

            // Load wake-word model according to user selection (preference key: selected_model)
            // Format: "assets:filename.onnx" or "user:filename.onnx". Default -> assets:hey_nabu.onnx
            val prefs = context.getSharedPreferences("wyoming_settings", Context.MODE_PRIVATE)
            val selected = requireNotNull(prefs.getString("selected_model", "assets:hey_nabu.onnx")) {
                "Preference 'selected_model' unexpectedly null"
            }

            try {
                if (selected.startsWith("assets:")) {
                    val name = selected.removePrefix("assets:")
                    wakeWordSession = ortEnvironment?.createSession(
                        context.assets.open("models/wakeword/$name").readBytes()
                    )
                    Log.i(TAG, "Loaded wake-word model from assets: $name")
                } else if (selected.startsWith("user:")) {
                    val name = selected.removePrefix("user:")
                    wakeWordSession = ortEnvironment?.createSession(
                        File(context.filesDir, "models/$name").readBytes()
                    )
                    Log.i(TAG, "Loaded wake-word model from user file: $name")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading selected wake-word model ($selected), attempting fallback", e)
                wakeWordSession = ortEnvironment?.createSession(
                    context.assets.open("models/wakeword/hey_nabu.onnx").readBytes()
                )
                Log.i(TAG, "Loaded fallback wake-word model: wakeword/hey_nabu.onnx")
            }
            
            // Pre-fill feature buffer with random data (like original Java code)
            val randomAudio = FloatArray(AudioConstants.SAMPLE_RATE * 4) { (Random().nextInt(2000) - 1000) / AudioConstants.PCM16_MAX.toFloat() }
            featureBuffer = getEmbeddings(randomAudio, 76, 8)
            
            Log.i(TAG, "✅ Wake word detector initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing wake word detector", e)
            throw e
        }
    }
    
    /**
     * Process audio chunk and return wake word score
     * @param audioChunk Short array from AudioRecord
     * @return Wake word score (0.0 to 1.0), or null if not ready
     */


    fun detectWakeWord(audioChunk: ShortArray): Float? {
        try {
            // Convert short to float [-1.0, 1.0]
            val floatBuffer = FloatArray(audioChunk.size) { i ->
                audioChunk[i] / AudioConstants.PCM16_MAX.toFloat()
            }
            return predictWakeWord(floatBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in detectWakeWord", e)
            Log.e(TAG, "Error detecting wake word", e)
            return null
        }
    }
    
    /**
     * Main prediction function (equivalent to predict_WakeWord in Java)
     */
    private fun predictWakeWord(audioBuffer: FloatArray): Float? {
        // Update features with new audio
        streamingFeatures(audioBuffer)
        
        // Get last 16 features for classification
        val features = getFeatures(FEATURE_FRAMES_NEEDED, -1) ?: return null
        
        // Run wake word classifier
        return runWakeWordModel(features)
    }
    
    /**
     * Process streaming audio and update feature buffer
     */
    private fun streamingFeatures(audioBuffer: FloatArray) {
        var audioBuf = audioBuffer
        accumulatedSamples = 0
        
        // Concatenate with remainder from previous call
        if (rawDataRemainder.isNotEmpty()) {
            Log.d(TAG, "Concatenating rawDataRemainder.size=${rawDataRemainder.size} with audioBuffer.size=${audioBuffer.size}")
            audioBuf = rawDataRemainder + audioBuffer
            rawDataRemainder = FloatArray(0)
        }
        
        // Process in chunks of 1280 samples
        if (accumulatedSamples + audioBuf.size >= N_SAMPLES_PER_FEATURE) {
            val remainder = (accumulatedSamples + audioBuf.size) % N_SAMPLES_PER_FEATURE
            
            if (remainder != 0) {
                Log.d(TAG, "Chunk remainder: $remainder (size: ${audioBuf.size})")
                // Split into even chunks and remainder
                val evenChunks = audioBuf.copyOfRange(0, audioBuf.size - remainder)
                bufferRawData(evenChunks)
                accumulatedSamples += evenChunks.size
                rawDataRemainder = audioBuf.copyOfRange(audioBuf.size - remainder, audioBuf.size)
            } else {
                // Perfect chunk size
                bufferRawData(audioBuf)
                accumulatedSamples += audioBuf.size
                rawDataRemainder = FloatArray(0)
            }
        } else {
            Log.d(TAG, "Not enough samples for a chunk, buffering all audioBuf (size: ${audioBuf.size})")
            accumulatedSamples += audioBuf.size
            bufferRawData(audioBuf)
        }
        
        // Process accumulated samples
        if (accumulatedSamples >= N_SAMPLES_PER_FEATURE && accumulatedSamples % N_SAMPLES_PER_FEATURE == 0) {
            streamingMelSpectrogram(accumulatedSamples)
            
            // Generate embeddings from mel spectrogram
            val numChunks = accumulatedSamples / N_SAMPLES_PER_FEATURE
            if (numChunks != 1) {
                Log.d(TAG, "numChunks=$numChunks")
            }

            for (i in numChunks - 1 downTo 0) {
                val ndx = melSpectrogramBuffer.size - 8 * i
                val start = maxOf(0, ndx - 76)
                val end = ndx
                
                // Extract 76 frames window
                val window = Array(1) { Array(76) { Array(32) { FloatArray(1) } } }
                
                for (j in start until end) {
                    for (k in 0 until 32) {
                        window[0][j - start][k][0] = melSpectrogramBuffer[j][k]
                    }
                }
                
                if (window[0].size == 76) {
                    try {
                        val newFeatures = generateEmbeddings(window)
                        
                        // Append to feature buffer
                        featureBuffer = if (featureBuffer == null) {
                            newFeatures
                        } else {
                            featureBuffer!! + newFeatures
                        }                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating embeddings for window $i", e)
                    }
                }
            }
            
            accumulatedSamples = 0
        }
        
        // Trim feature buffer to max length (120)
        featureBuffer?.let { buffer ->
            if (buffer.size > 120) {
                featureBuffer = buffer.copyOfRange(buffer.size - 120, buffer.size)
            }
        }
    }
    
    /**
     * Buffer raw audio data
     */
    private fun bufferRawData(data: FloatArray) {
        // Remove old data if buffer is full
        while (rawDataBuffer.size + data.size > AudioConstants.SAMPLE_RATE * 10) {
            rawDataBuffer.poll()
        }
        
        // Add new data
        for (value in data) {
            rawDataBuffer.offer(value)
        }
    }
    
    /**
     * Update mel spectrogram buffer with new audio
     */
    private fun streamingMelSpectrogram(nSamples: Int) {
        if (rawDataBuffer.size < 400) {
            throw IllegalArgumentException("Need at least 400 samples @ 16kHz (25 ms)!")
        }
        
        // Get last nSamples + 480 samples
        val tempArray = FloatArray(nSamples + 480)
        val rawDataArray = rawDataBuffer.toTypedArray()
        val startIdx = maxOf(0, rawDataArray.size - nSamples - 480)
        
        for (i in startIdx until rawDataArray.size) {
            tempArray[i - startIdx] = rawDataArray[i]
        }
        
        // Get mel spectrogram from ONNX model
        val newMelSpec = getMelSpectrogram(tempArray)
        
        // Concatenate with existing buffer
        melSpectrogramBuffer = melSpectrogramBuffer + newMelSpec
        
        // Trim to max length (10 * 97 = 970)
        if (melSpectrogramBuffer.size > 970) {
            val trimmed = Array(970) { FloatArray(32) }
            System.arraycopy(melSpectrogramBuffer, melSpectrogramBuffer.size - 970, trimmed, 0, 970)
            melSpectrogramBuffer = trimmed
        }
    }
    
    /**
     * Run mel spectrogram ONNX model
     */
    private fun getMelSpectrogram(audioData: FloatArray): Array<FloatArray> {
        val env = ortEnvironment ?: throw IllegalStateException("ONNX environment not initialized")
        val session = melSpectrogramSession ?: throw IllegalStateException("Mel spectrogram session not initialized")
        
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(audioData),
            longArrayOf(1, audioData.size.toLong())
        )
        
        val results = session.run(mapOf(session.inputNames.first() to inputTensor))
        @Suppress("UNCHECKED_CAST")
        val output = results[0].value as Array<Array<Array<FloatArray>>>
        
        inputTensor.close()
        results.close()
        
        // Squeeze [1, 1, time, freq] -> [time, freq]
        val squeezed = output[0][0]
        
        // Apply transform: x / 10.0 + 2.0
        return Array(squeezed.size) { i ->
            FloatArray(squeezed[i].size) { j ->
                squeezed[i][j] / 10.0f + 2.0f
            }
        }
    }
    
    /**
     * Generate embeddings from audio (for initialization)
     */
    private fun getEmbeddings(audio: FloatArray, windowSize: Int, stepSize: Int): Array<FloatArray> {
        val spec = getMelSpectrogram(audio)
        val windows = mutableListOf<Array<FloatArray>>()
        
        // Create sliding windows
        for (i in 0..spec.size - windowSize step stepSize) {
            val window = Array(windowSize) { FloatArray(spec[0].size) }
            
            for (j in 0 until windowSize) {
                System.arraycopy(spec[i + j], 0, window[j], 0, spec[0].size)
            }
            
            if (window.size == windowSize) {
                windows.add(window)
            }
        }
        
        // Convert to 4D batch: [batch, 76, 32, 1]
        val batch = Array(windows.size) { i ->
            Array(windowSize) { j ->
                Array(32) { k ->
                    FloatArray(1) { windows[i][j][k] }
                }
            }
        }
        
        return generateEmbeddings(batch)
    }
    
    /**
     * Run embedding ONNX model
     */
    private fun generateEmbeddings(input: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
        val env = ortEnvironment ?: throw IllegalStateException("ONNX environment not initialized")
        val session = embeddingSession ?: throw IllegalStateException("Embedding session not initialized")
        
        val inputTensor = OnnxTensor.createTensor(env, input)
        val results = session.run(mapOf("input_1" to inputTensor))
        @Suppress("UNCHECKED_CAST")
        val rawOutput = results[0].value as Array<Array<Array<FloatArray>>>
        
        inputTensor.close()
        results.close()
        
        // Reshape from [batch, 1, 1, 96] to [batch, 96]
        return Array(rawOutput.size) { i ->
            rawOutput[i][0][0]
        }
    }
    
    /**
     * Get features for wake word classification
     * @param nFeatureFrames Number of frames to get (typically 16)
     * @param startNdx Start index (-1 for last N frames)
     */
    private fun getFeatures(nFeatureFrames: Int, startNdx: Int): Array<Array<FloatArray>>? {
        val buffer = featureBuffer ?: return null
        
        val start: Int
        val end: Int
        
        if (startNdx != -1) {
            start = startNdx
            end = if (startNdx + nFeatureFrames != 0) startNdx + nFeatureFrames else buffer.size
        } else {
            start = maxOf(0, buffer.size - nFeatureFrames)
            end = buffer.size
        }
        
        val length = end - start
        val result = Array(1) { Array(length) { FloatArray(buffer[0].size) } }
        
        for (i in 0 until length) {
            System.arraycopy(buffer[start + i], 0, result[0][i], 0, buffer[start + i].size)
        }
        
        return result
    }
    
    /**
     * Run wake word classification model
     */
    private fun runWakeWordModel(features: Array<Array<FloatArray>>): Float {
        val env = ortEnvironment ?: throw IllegalStateException("ONNX environment not initialized")
        val session = wakeWordSession ?: throw IllegalStateException("Wake word session not initialized")

        val inputTensor = OnnxTensor.createTensor(env, features)
        val results = session.run(mapOf(session.inputNames.first() to inputTensor))
        @Suppress("UNCHECKED_CAST")
        val output = results[0].value as Array<FloatArray>

        inputTensor.close()
        results.close()

        val score = output[0][0]

        if (score >= 0.001f) {
            if (score > THRESHOLD) {
                Log.i(TAG, "🎤 WAKE WORD DETECTED!. Score: %.5f (threshold: %.2f)"
                    .format(score, THRESHOLD))
            }
            else {
                Log.d(TAG, "Wake word score below threshold. Score: %.5f (threshold: %.2f)"
                    .format(score, THRESHOLD))      
            }
        }

        return score
    }

    
    fun cleanup() {
        try {
            melSpectrogramSession?.close()
            embeddingSession?.close()
            wakeWordSession?.close()
            ortEnvironment?.close()

            rawDataBuffer.clear()
            rawDataRemainder = FloatArray(0)
            featureBuffer = null

            Log.d(TAG, "Wake word detector cleaned up")

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up wake word detector", e)
        }
    }
}
