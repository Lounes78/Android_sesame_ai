package com.sesame.voicechat

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioFileProcessor(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioFileProcessor"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 1024 * 2 // 1024 samples * 2 bytes per sample
    }
    
    data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataSize: Int
    )
    
    /**
     * Loads and processes a WAV file from the assets or raw resources
     * Returns audio chunks ready to be sent to the WebSocket
     */
    fun loadWavFile(fileName: String): List<ByteArray>? {
        return try {
            val inputStream = context.assets.open(fileName)
            processWavFile(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load WAV file: $fileName", e)
            null
        }
    }
    
    /**
     * Loads and processes a WAV file from a specific path
     */
    fun loadWavFileFromPath(filePath: String): List<ByteArray>? {
        return try {
            val inputStream = context.assets.open(filePath)
            processWavFile(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load WAV file from path: $filePath", e)
            null
        }
    }
    
    private fun processWavFile(inputStream: InputStream): List<ByteArray>? {
        return try {
            val wavData = inputStream.readBytes()
            inputStream.close()
            
            Log.d(TAG, "Loaded WAV file, size: ${wavData.size} bytes")
            
            // Parse WAV header
            val header = parseWavHeader(wavData) ?: return null
            Log.d(TAG, "WAV Header: $header")
            
            // Extract audio data (skip header, typically 44 bytes)
            val headerSize = findDataChunkOffset(wavData) + 8 // +8 for "data" + size
            val audioData = wavData.sliceArray(headerSize until wavData.size)
            
            Log.d(TAG, "Audio data size: ${audioData.size} bytes")
            
            // Convert to target format if needed
            val processedAudio = if (header.sampleRate != TARGET_SAMPLE_RATE) {
                Log.d(TAG, "Resampling from ${header.sampleRate}Hz to ${TARGET_SAMPLE_RATE}Hz")
                resampleAudio(audioData, header.sampleRate, TARGET_SAMPLE_RATE, header.channels)
            } else {
                audioData
            }
            
            // Convert to mono if stereo
            val monoAudio = if (header.channels == 2) {
                Log.d(TAG, "Converting stereo to mono")
                convertStereoToMono(processedAudio)
            } else {
                processedAudio
            }
            
            // Split into chunks
            val chunks = splitIntoChunks(monoAudio, CHUNK_SIZE)
            Log.d(TAG, "Split audio into ${chunks.size} chunks of ${CHUNK_SIZE} bytes each")
            
            chunks
        } catch (e: Exception) {
            Log.e(TAG, "Error processing WAV file", e)
            null
        }
    }
    
    private fun parseWavHeader(wavData: ByteArray): WavHeader? {
        if (wavData.size < 44) {
            Log.e(TAG, "WAV file too small")
            return null
        }
        
        // Check RIFF header
        val riffHeader = String(wavData.sliceArray(0..3))
        if (riffHeader != "RIFF") {
            Log.e(TAG, "Not a valid RIFF file")
            return null
        }
        
        // Check WAVE format
        val waveHeader = String(wavData.sliceArray(8..11))
        if (waveHeader != "WAVE") {
            Log.e(TAG, "Not a valid WAVE file")
            return null
        }
        
        val buffer = ByteBuffer.wrap(wavData).order(ByteOrder.LITTLE_ENDIAN)
        
        // Parse format chunk (assuming standard 44-byte header)
        val sampleRate = buffer.getInt(24)
        val channels = buffer.getShort(22).toInt()
        val bitsPerSample = buffer.getShort(34).toInt()
        val dataSize = buffer.getInt(40)
        
        return WavHeader(sampleRate, channels, bitsPerSample, dataSize)
    }
    
    private fun findDataChunkOffset(wavData: ByteArray): Int {
        // Look for "data" chunk
        for (i in 12 until wavData.size - 4) {
            if (wavData[i] == 'd'.code.toByte() &&
                wavData[i + 1] == 'a'.code.toByte() &&
                wavData[i + 2] == 't'.code.toByte() &&
                wavData[i + 3] == 'a'.code.toByte()) {
                return i
            }
        }
        return 44 // Default header size if not found
    }
    
    private fun resampleAudio(audioData: ByteArray, fromRate: Int, toRate: Int, channels: Int): ByteArray {
        // Simple linear interpolation resampling
        val samplesCount = audioData.size / (2 * channels) // 16-bit samples
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val newSamplesCount = (samplesCount / ratio).toInt()
        
        val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(newSamplesCount * 2 * channels).order(ByteOrder.LITTLE_ENDIAN)
        
        for (i in 0 until newSamplesCount) {
            val sourceIndex = (i * ratio).toInt()
            if (sourceIndex < samplesCount) {
                for (ch in 0 until channels) {
                    val sampleIndex = (sourceIndex * channels + ch) * 2
                    if (sampleIndex + 1 < audioData.size) {
                        val sample = buffer.getShort(sampleIndex)
                        output.putShort(sample)
                    }
                }
            }
        }
        
        return output.array()
    }
    
    private fun convertStereoToMono(stereoData: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(stereoData).order(ByteOrder.LITTLE_ENDIAN)
        val monoSize = stereoData.size / 2
        val monoBuffer = ByteBuffer.allocate(monoSize).order(ByteOrder.LITTLE_ENDIAN)
        
        for (i in 0 until stereoData.size step 4) { // 4 bytes = 2 samples (left + right)
            if (i + 3 < stereoData.size) {
                val left = buffer.getShort(i)
                val right = buffer.getShort(i + 2)
                val mono = ((left + right) / 2).toShort()
                monoBuffer.putShort(mono)
            }
        }
        
        return monoBuffer.array()
    }
    
    private fun splitIntoChunks(audioData: ByteArray, chunkSize: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        
        for (i in audioData.indices step chunkSize) {
            val endIndex = minOf(i + chunkSize, audioData.size)
            val chunk = audioData.sliceArray(i until endIndex)
            
            // Pad the last chunk with zeros if needed
            if (chunk.size < chunkSize) {
                val paddedChunk = ByteArray(chunkSize)
                chunk.copyInto(paddedChunk)
                chunks.add(paddedChunk)
            } else {
                chunks.add(chunk)
            }
        }
        
        return chunks
    }
}