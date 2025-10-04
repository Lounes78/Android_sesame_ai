package com.sesame.voicechat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class AudioPlayer(private var sampleRate: Int = 24000) {
    
    companion object {
        private const val TAG = "AudioPlayer"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }
    
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playbackThread: Thread? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    
    // BUFFER OPTIMIZATION SETTINGS
    private var minBufferSize = 5      // Minimum chunks before starting playback
    private var targetBufferSize = 10  // Optimal buffer size for smooth playback
    private var maxBufferSize = 20     // Maximum before dropping old chunks
    private var jitterBufferSize = 3   // Extra chunks to handle network jitter
    
    // Playback state management
    private var playbackStarted = false
    private var bufferUnderrunCount = 0
    private var bufferOverrunCount = 0
    
    // Timing and synchronization
    private var playbackStartTime = 0L
    private var expectedPlaybackTime = 0L
    private var chunkDurationMs = 0L  // Will be calculated based on sample rate
    
    // Performance tracking
    private var chunksReceived = 0
    private var chunksPlayed = 0
    private var jitterCorrections = 0
    private var lastBufferLogTime = 0L
    
    var onErrorCallback: ((String) -> Unit)? = null
    
    fun startPlayback(): Boolean {
        return try {
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_MULTIPLIER
            
            if (bufferSize == AudioTrack.ERROR || bufferSize == AudioTrack.ERROR_BAD_VALUE) {
                onErrorCallback?.invoke("Invalid buffer size for playback")
                return false
            }
            
            // Calculate timing parameters
            calculateTimingParameters()
            
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()
            
            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                onErrorCallback?.invoke("AudioTrack initialization failed")
                return false
            }
            
            // DON'T start playing immediately - wait for buffer to fill
            isPlaying = true
            
            // Reset state
            playbackStarted = false
            bufferUnderrunCount = 0
            bufferOverrunCount = 0
            chunksReceived = 0
            chunksPlayed = 0
            jitterCorrections = 0
            lastBufferLogTime = System.currentTimeMillis()
            
            startPlaybackThread()
            
            Log.d(TAG, "Buffer-optimized audio playback started with sample rate: $sampleRate")
            Log.d(TAG, "Buffer settings: min=$minBufferSize, target=$targetBufferSize, max=$maxBufferSize")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            onErrorCallback?.invoke("Failed to start playback: ${e.message}")
            false
        }
    }
    
    fun stopPlayback() {
        isPlaying = false
        playbackThread?.interrupt()
        
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            audioQueue.clear()
            Log.d(TAG, "Audio playback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
    }
    
    fun queueAudioData(audioData: ByteArray) {
        if (isPlaying) {
            val receiveTime = System.currentTimeMillis()
            
            synchronized(audioQueue) {
                // Buffer overflow management
                if (audioQueue.size >= maxBufferSize) {
                    audioQueue.poll() // Remove oldest chunk
                    bufferOverrunCount++
                    Log.d(TAG, "Buffer overflow - dropped oldest chunk")
                }
                
                audioQueue.offer(audioData)
                chunksReceived++
                
                val bufferSize = audioQueue.size
                
                // Start playback if we have enough buffer
                if (!playbackStarted && bufferSize >= minBufferSize) {
                    audioTrack?.play()  // NOW start the AudioTrack
                    playbackStarted = true
                    playbackStartTime = System.currentTimeMillis()
                    expectedPlaybackTime = playbackStartTime
                    Log.i(TAG, "🎵 AudioTrack started with $bufferSize chunks pre-filled")
                }
            }
            
            // Monitor buffer health periodically
            monitorBufferHealth()
        }
    }
    
    fun updateSampleRate(newSampleRate: Int) {
        if (newSampleRate != sampleRate) {
            Log.d(TAG, "Updating sample rate from $sampleRate to $newSampleRate")
            val wasPlaying = isPlaying
            
            if (wasPlaying) {
                stopPlayback()
            }
            
            sampleRate = newSampleRate
            
            if (wasPlaying) {
                startPlayback()
            }
        }
    }
    
    private fun startPlaybackThread() {
        playbackThread = thread {
            while (isPlaying && !Thread.currentThread().isInterrupted) {
                try {
                    // Wait for buffer to be ready
                    if (!playbackStarted) {
                        Thread.sleep(10)
                        continue
                    }
                    
                    // Get next chunk
                    val audioData = getNextAudioChunk()
                    
                    if (audioData != null) {
                        // Calculate expected timing for smooth playback
                        val currentTime = System.currentTimeMillis()
                        
                        // If we're ahead of expected time, wait
                        if (currentTime < expectedPlaybackTime) {
                            val sleepTime = expectedPlaybackTime - currentTime
                            if (sleepTime > 0 && sleepTime < 100) { // Reasonable sleep time
                                Thread.sleep(sleepTime)
                            }
                        }
                        
                        // Play the chunk
                        val bytesWritten = audioTrack?.write(audioData, 0, audioData.size) ?: 0
                        
                        if (bytesWritten < 0) {
                            Log.w(TAG, "AudioTrack write error: $bytesWritten")
                        }
                        
                        // Update expected time for next chunk
                        expectedPlaybackTime += chunkDurationMs
                        
                        // If we're too far behind, reset timing to prevent drift
                        if (currentTime > expectedPlaybackTime + 100) {
                            expectedPlaybackTime = currentTime
                            jitterCorrections++
                            Log.d(TAG, "Corrected playback timing drift")
                        }
                        
                    } else {
                        // No audio available - buffer underrun
                        if (playbackStarted) {
                            // If buffer runs completely dry, pause and restart pre-fill
                            Log.w(TAG, "🛑 Buffer completely empty - pausing AudioTrack for refill")
                            audioTrack?.pause()
                            playbackStarted = false
                            expectedPlaybackTime = 0L
                        } else {
                            Thread.sleep(20) // Wait longer when not playing
                        }
                    }
                    
                } catch (e: InterruptedException) {
                    // Thread was interrupted, exit loop
                    break
                } catch (e: Exception) {
                    if (isPlaying) {
                        Log.e(TAG, "Error during audio playback", e)
                        onErrorCallback?.invoke("Playback error: ${e.message}")
                    }
                    break
                }
            }
        }
    }
    
    private fun calculateTimingParameters() {
        // Calculate chunk duration for output rate (assuming 1024 samples per chunk)
        val chunkSamples = 1024
        chunkDurationMs = (chunkSamples * 1000L) / sampleRate
        
        // Calculate how many chunks we need for smooth playback
        val targetLatencyMs = 200L // Target 200ms latency for good quality
        val chunksForLatency = (targetLatencyMs / chunkDurationMs).toInt()
        
        // Adjust buffer sizes based on chunk duration
        minBufferSize = max(3, chunksForLatency / 3)
        targetBufferSize = max(5, chunksForLatency / 2)
        maxBufferSize = max(10, chunksForLatency)
        
        Log.i(TAG, "Buffer timing: chunk_duration=${chunkDurationMs}ms, target_buffer=$targetBufferSize chunks (${targetBufferSize * chunkDurationMs}ms)")
    }
    
    private fun monitorBufferHealth() {
        val currentTime = System.currentTimeMillis()
        
        // Only log every 5 seconds to avoid spam
        if (currentTime - lastBufferLogTime < 5000) {
            return
        }
        
        val bufferSize = audioQueue.size
        
        // Calculate buffer health percentage
        val bufferHealth = (bufferSize.toFloat() / targetBufferSize) * 100
        
        // Determine buffer status
        val status = when {
            bufferSize < minBufferSize -> "STARVING"
            bufferSize > (maxBufferSize * 0.8).toInt() -> "OVERFLOWING"
            bufferSize >= targetBufferSize -> "HEALTHY"
            else -> "FILLING"
        }
        
        Log.i(TAG, "Buffer: $bufferSize/$maxBufferSize chunks | Health: ${bufferHealth.toInt()}% | Status: $status | Underruns: $bufferUnderrunCount, Overruns: $bufferOverrunCount")
        
        lastBufferLogTime = currentTime
    }
    
    private fun getNextAudioChunk(): ByteArray? {
        synchronized(audioQueue) {
            if (audioQueue.isEmpty()) {
                return null
            }
            
            val audioData = audioQueue.poll()
            chunksPlayed++
            
            // Check for buffer underrun
            if (audioQueue.size < minBufferSize) {
                bufferUnderrunCount++
                if (playbackStarted) {
                    Log.w(TAG, "Buffer underrun - only ${audioQueue.size} chunks left")
                }
            }
            
            return audioData
        }
    }
    
    fun isPlaying(): Boolean = isPlaying
    
    fun getQueueSize(): Int = audioQueue.size
    
    fun clearQueue() {
        audioQueue.clear()
    }
    
    // Expose buffer health metrics for external monitoring
    fun getBufferHealth(): Map<String, Any> {
        return mapOf(
            "bufferSize" to audioQueue.size,
            "targetBufferSize" to targetBufferSize,
            "maxBufferSize" to maxBufferSize,
            "chunksReceived" to chunksReceived,
            "chunksPlayed" to chunksPlayed,
            "bufferUnderruns" to bufferUnderrunCount,
            "bufferOverruns" to bufferOverrunCount,
            "jitterCorrections" to jitterCorrections,
            "playbackStarted" to playbackStarted
        )
    }
}