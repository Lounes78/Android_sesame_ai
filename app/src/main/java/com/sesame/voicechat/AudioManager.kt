package com.sesame.voicechat

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

class AudioManager {
    
    companion object {
        private const val TAG = "AudioManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    
    // Voice activity detection - highly sensitive for better pickup
    private var amplitudeThreshold = 100.0  // Very sensitive threshold for quiet speech
    private var silenceCounter = 0
    private val silenceLimit = 15 // Quick response to speech/silence transitions
    
    // DEBUG MODE - forces voice detection for troubleshooting
    private var debugMode = false  // Disabled - using improved VAD sensitivity
    
    // CAPTURE OPTIMIZATION SETTINGS for car compatibility
    private var chunkSize = 1024  // Standard chunk size
    private var processingDelayCount = 0
    private var captureErrors = 0
    private var lastStatsTime = 0L
    private var chunksProcessed = 0
    
    var onAudioDataCallback: ((ByteArray, Boolean) -> Unit)? = null
    var onErrorCallback: ((String) -> Unit)? = null
    
    fun startRecording(): Boolean {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_MULTIPLIER
            
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                onErrorCallback?.invoke("Invalid buffer size")
                return false
            }
            
            // Try car-specific audio source if available
            val audioSource = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            
            Log.i(TAG, "🎤 Using audio source: $audioSource")
            
            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onErrorCallback?.invoke("AudioRecord initialization failed")
                return false
            }
            
            audioRecord?.startRecording()
            isRecording = true
            
            // Reset stats
            processingDelayCount = 0
            captureErrors = 0
            chunksProcessed = 0
            lastStatsTime = System.currentTimeMillis()
            
            startRecordingThread(bufferSize)
            
            Log.i(TAG, "★ AUDIO RECORDING STARTED ★")
            Log.i(TAG, "Settings: chunk_size=$chunkSize, buffer_size=$bufferSize, threshold=$amplitudeThreshold")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            onErrorCallback?.invoke("Failed to start recording: ${e.message}")
            false
        }
    }
    
    fun stopRecording() {
        isRecording = false
        recordingThread?.interrupt()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "Audio recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }
    
    private fun startRecordingThread(bufferSize: Int) {
        recordingThread = Thread {
            // Use standard chunk size for better car compatibility
            val buffer = ByteArray(chunkSize * 2) // 1024 samples * 2 bytes per sample
            
            while (isRecording && !Thread.currentThread().isInterrupted) {
                try {
                    val startTime = System.currentTimeMillis()
                    
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (bytesRead > 0) {
                        // Check AudioRecord state periodically
                        if (chunksProcessed % 100 == 0) {
                            val state = audioRecord?.state
                            val recordingState = audioRecord?.recordingState
                            Log.i(TAG, "🎤 AudioRecord State: $state, Recording: $recordingState")
                        }
                        
                        // Voice activity detection
                        val hasVoice = if (debugMode) {
                            // In debug mode, still run detection but force voice result
                            detectVoiceActivity(buffer, bytesRead) // Run detection for logging
                            if (chunksProcessed % 100 == 0) {
                                Log.i(TAG, "🐛 DEBUG MODE: Forcing voice detection (chunk $chunksProcessed)")
                            }
                            true
                        } else {
                            detectVoiceActivity(buffer, bytesRead)
                        }
                        
                        onAudioDataCallback?.invoke(buffer.copyOf(bytesRead), hasVoice)
                        
                        chunksProcessed++
                        
                        // Monitor processing time
                        val processingTime = System.currentTimeMillis() - startTime
                        if (processingTime > 10) { // More than 10ms is concerning
                            processingDelayCount++
                        }
                        
                        // Log stats periodically
                        monitorCapturePerformance()
                    } else {
                        Log.e(TAG, "❌ AudioRecord.read() returned $bytesRead - microphone failure!")
                    }
                    
                } catch (e: Exception) {
                    captureErrors++
                    if (isRecording) {
                        Log.e(TAG, "Error reading audio data", e)
                        onErrorCallback?.invoke("Error reading audio: ${e.message}")
                    }
                    break
                }
            }
        }
        recordingThread?.start()
    }
    
    private fun detectVoiceActivity(buffer: ByteArray, length: Int): Boolean {
        // Convert bytes to 16-bit samples and calculate RMS
        var sum = 0.0
        var sampleCount = 0
        var maxSample = 0
        var minSample = 0
        
        for (i in 0 until length step 2) {
            if (i + 1 < length) {
                // Convert two bytes to 16-bit sample (little endian)
                val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                val signedSample = if (sample > 32767) sample - 65536 else sample
                
                sum += signedSample * signedSample
                sampleCount++
                
                if (signedSample > maxSample) maxSample = signedSample
                if (signedSample < minSample) minSample = signedSample
            }
        }
        
        if (sampleCount == 0) {
            Log.w(TAG, "❌ No audio samples in buffer!")
            return false
        }
        
        val rms = sqrt(sum / sampleCount)
        val amplitude = maxSample - minSample
        
        // Reduced logging for better performance - only log occasionally for monitoring
        if (chunksProcessed % 200 == 0) { // Every ~30 seconds instead of every 1.4 seconds
            Log.d(TAG, "🎤 Audio levels: RMS=${rms.toInt()}, Threshold=${amplitudeThreshold.toInt()}")
        }
        
        return if (rms > amplitudeThreshold) {
            // Voice detected
            silenceCounter = 0
            // Only log voice detection occasionally to reduce overhead
            if (silenceCounter == 0) { // Only log when transitioning from silence to voice
                Log.d(TAG, "🗣️ Voice detected: RMS ${rms.toInt()}")
            }
            true
        } else {
            // Silence detected
            silenceCounter++
            // Minimal silence logging for performance
            if (chunksProcessed % 500 == 0 && silenceCounter > silenceLimit) { // Very occasional
                Log.v(TAG, "Audio quiet: RMS=${rms.toInt()}")
            }
            // Still consider it "voice" for a few chunks to avoid cutting off speech
            silenceCounter < silenceLimit
        }
    }
    
    fun setAmplitudeThreshold(threshold: Double) {
        amplitudeThreshold = threshold
    }
    
    private fun monitorCapturePerformance() {
        val currentTime = System.currentTimeMillis()
        
        // Only log every 5 seconds for more frequent debugging
        if (currentTime - lastStatsTime < 5000) {
            return
        }
        
        val timeElapsed = (currentTime - lastStatsTime) / 1000.0
        val delaysPerSec = if (timeElapsed > 0) processingDelayCount / timeElapsed else 0.0
        val chunksPerSec = if (timeElapsed > 0) chunksProcessed / timeElapsed else 0.0
        
        val debugInfo = if (debugMode) " [DEBUG MODE]" else ""
        Log.i(TAG, "🎤 Capture: ${chunksPerSec.toInt()} chunks/s, ${delaysPerSec.toInt()} delays/s, $captureErrors errors$debugInfo")
        
        // Reset counters
        processingDelayCount = 0
        captureErrors = 0
        chunksProcessed = 0
        lastStatsTime = currentTime
    }
    
    fun isRecording(): Boolean = isRecording
    
    // Expose capture metrics for external monitoring
    fun getCaptureMetrics(): Map<String, Any> {
        return mapOf(
            "isRecording" to isRecording,
            "chunksProcessed" to chunksProcessed,
            "processingDelays" to processingDelayCount,
            "captureErrors" to captureErrors,
            "amplitudeThreshold" to amplitudeThreshold,
            "silenceCounter" to silenceCounter
        )
    }
    
    // Allow dynamic adjustment of capture settings for car optimization
    fun adjustForCarMode(useCarOptimizations: Boolean) {
        if (useCarOptimizations) {
            // Car-optimized settings - VERY sensitive for car voice pickup
            chunkSize = 2048  // Larger chunks for car systems
            amplitudeThreshold = 150.0  // Very sensitive - picks up quiet speech
            Log.i(TAG, "🚗 CAR MODE: chunk_size=$chunkSize, threshold=$amplitudeThreshold (HIGHLY SENSITIVE)")
        } else {
            // Phone-optimized settings
            chunkSize = 1024  // Standard chunk size
            amplitudeThreshold = 100.0  // Very sensitive for phone use
            Log.i(TAG, "📱 PHONE MODE: chunk_size=$chunkSize, threshold=$amplitudeThreshold (HIGHLY SENSITIVE)")
        }
    }
    
    // Control debug mode for troubleshooting
    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
        Log.i(TAG, if (enabled) "🐛 DEBUG MODE ENABLED - Forcing voice detection" else "🐛 DEBUG MODE DISABLED - Using normal voice detection")
    }
}