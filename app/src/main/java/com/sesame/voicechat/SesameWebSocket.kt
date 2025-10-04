package com.sesame.voicechat

import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SesameWebSocket(
    private val idToken: String,
    private val character: String = "Miles",
    private val clientName: String = "RP-Android"
) {
    
    companion object {
        private const val TAG = "SesameWebSocket"
        private const val WS_URL = "wss://sesameai.app/agent-service-0/v1/connect"
        private const val CLIENT_SAMPLE_RATE = 16000
        private const val DEFAULT_SERVER_SAMPLE_RATE = 24000
    }
    
    // Connection state
    private var webSocket: WebSocket? = null
    private var sessionId: String? = null
    private var callId: String? = null
    private var isConnected = false
    
    // Audio settings
    var serverSampleRate = DEFAULT_SERVER_SAMPLE_RATE
        private set
    private var audioCodec = "none"
    
    // Audio buffer for received audio
    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()
    
    // Message tracking
    private var lastSentMessageType: String? = null
    private var receivedSinceLastSent = false
    private var firstAudioReceived = false
    
    // Callbacks
    var onConnectCallback: (() -> Unit)? = null
    var onDisconnectCallback: (() -> Unit)? = null
    var onErrorCallback: ((String) -> Unit)? = null
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    fun connect(): Boolean {
        try {
            val params = mapOf(
                "id_token" to idToken,
                "client_name" to clientName,
                "usercontext" to """{"timezone": "America/Chicago"}""",
                "character" to character
            )
            
            val queryString = params.map { (key, value) ->
                "$key=${URLEncoder.encode(value, "UTF-8")}"
            }.joinToString("&")
            
            val wsUrl = "$WS_URL?$queryString"
            
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("Origin", "https://www.sesame.com")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()
            
            webSocket = client.newWebSocket(request, WebSocketListener())
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            onErrorCallback?.invoke("Connection failed: ${e.message}")
            return false
        }
    }
    
    fun disconnect() {
        if (sessionId != null && callId != null) {
            sendCallDisconnect()
        }
        webSocket?.close(1000, "User requested disconnect")
        webSocket = null
        isConnected = false
    }
    
    fun sendAudioData(audioData: ByteArray): Boolean {
        if (sessionId == null || callId == null) return false
        
        val encodedData = Base64.encodeToString(audioData, Base64.NO_WRAP)
        return sendAudio(encodedData)
    }
    
    fun getNextAudioChunk(): ByteArray? {
        return audioBuffer.poll()
    }
    
    fun isConnected(): Boolean = isConnected
    
    private inner class WebSocketListener : okhttp3.WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened")
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val data = JSONObject(text)
                val messageType = data.optString("type")
                
                when (messageType) {
                    "initialize" -> handleInitialize(data)
                    "call_connect_response" -> handleCallConnectResponse(data)
                    "ping_response" -> handlePingResponse(data)
                    "audio" -> handleAudio(data)
                    "call_disconnect_response" -> handleCallDisconnectResponse(data)
                    else -> Log.d(TAG, "Received message type: $messageType")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message", e)
            }
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error", t)
            isConnected = false
            onErrorCallback?.invoke("WebSocket error: ${t.message}")
            onDisconnectCallback?.invoke()
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code - $reason")
            isConnected = false
            onDisconnectCallback?.invoke()
        }
    }
    
    private fun handleInitialize(data: JSONObject) {
        sessionId = data.optString("session_id")
        Log.d(TAG, "Session ID: $sessionId")
        
        sendClientLocationState()
        sendCallConnect()
    }
    
    private fun handleCallConnectResponse(data: JSONObject) {
        sessionId = data.optString("session_id")
        callId = data.optString("call_id")
        
        val content = data.optJSONObject("content")
        content?.let {
            serverSampleRate = it.optInt("sample_rate", DEFAULT_SERVER_SAMPLE_RATE)
            audioCodec = it.optString("audio_codec", "none")
        }
        
        Log.d(TAG, "Connected: Session ID: $sessionId, Call ID: $callId")
        isConnected = true
        onConnectCallback?.invoke()
    }
    
    private fun handlePingResponse(data: JSONObject) {
        // Handle ping response if needed
    }
    
    private fun handleAudio(data: JSONObject) {
        val content = data.optJSONObject("content")
        val audioData = content?.optString("audio_data")
        
        if (!audioData.isNullOrEmpty()) {
            try {
                val audioBytes = Base64.decode(audioData, Base64.DEFAULT)
                
                // Add to buffer, removing oldest if buffer gets too large
                if (audioBuffer.size > 100) {
                    audioBuffer.poll()
                }
                audioBuffer.offer(audioBytes)
                
                if (!firstAudioReceived) {
                    firstAudioReceived = true
                    Log.d(TAG, "First audio received, sending initialization chunks")
                    
                    // Send 2 all-A chunks to initialize audio stream
                    val chunkOfAs = "A".repeat(1707) + "="
                    sendAudio(chunkOfAs)
                    sendAudio(chunkOfAs)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio", e)
            }
        }
    }
    
    private fun handleCallDisconnectResponse(data: JSONObject) {
        Log.d(TAG, "Call disconnected")
        callId = null
        isConnected = false
        onDisconnectCallback?.invoke()
    }
    
    private fun sendPing() {
        if (sessionId == null) return
        
        val message = JSONObject().apply {
            put("type", "ping")
            put("session_id", sessionId)
            put("call_id", callId)
            put("request_id", generateRequestId())
            put("content", "ping")
        }
        
        sendMessage(message)
    }
    
    private fun sendClientLocationState() {
        if (sessionId == null) return
        
        val content = JSONObject().apply {
            put("latitude", 0)
            put("longitude", 0)
            put("address", "")
            put("timezone", "America/Chicago")
        }
        
        val message = JSONObject().apply {
            put("type", "client_location_state")
            put("session_id", sessionId)
            put("call_id", JSONObject.NULL)
            put("content", content)
        }
        
        sendMessage(message)
    }
    
    private fun sendAudio(audioData: String): Boolean {
        if (sessionId == null || callId == null) return false
        
        val content = JSONObject().apply {
            put("audio_data", audioData)
        }
        
        val message = JSONObject().apply {
            put("type", "audio")
            put("session_id", sessionId)
            put("call_id", callId)
            put("content", content)
        }
        
        return sendData(message)
    }
    
    private fun sendCallConnect() {
        if (sessionId == null) return
        
        val clientMetadata = JSONObject().apply {
            put("language", "en-US")
            put("user_agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            put("mobile_browser", true)
            put("media_devices", getMediaDevicesJsonArray())
        }
        
        val settings = JSONObject().apply {
            put("character", character)
        }
        
        val content = JSONObject().apply {
            put("sample_rate", CLIENT_SAMPLE_RATE)
            put("audio_codec", "none")
            put("reconnect", false)
            put("is_private", false)
            put("client_name", clientName)
            put("settings", settings)
            put("client_metadata", clientMetadata)
        }
        
        val message = JSONObject().apply {
            put("type", "call_connect")
            put("session_id", sessionId)
            put("call_id", JSONObject.NULL)
            put("request_id", generateRequestId())
            put("content", content)
        }
        
        sendMessage(message)
    }
    
    private fun sendCallDisconnect() {
        if (sessionId == null || callId == null) return
        
        val content = JSONObject().apply {
            put("reason", "user_request")
        }
        
        val message = JSONObject().apply {
            put("type", "call_disconnect")
            put("session_id", sessionId)
            put("call_id", callId)
            put("request_id", generateRequestId())
            put("content", content)
        }
        
        sendMessage(message)
    }
    
    private fun sendData(message: JSONObject): Boolean {
        try {
            val dataType = message.optString("type")
            
            // Send pings for non-control messages after connection is established
            if (callId != null && dataType !in listOf("ping", "call_connect", "call_disconnect")) {
                if (lastSentMessageType == null || 
                    receivedSinceLastSent || 
                    dataType != lastSentMessageType) {
                    sendPing()
                }
                
                lastSentMessageType = dataType
                receivedSinceLastSent = false
            }
            
            return sendMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data", e)
            return false
        }
    }
    
    private fun sendMessage(message: JSONObject): Boolean {
        return try {
            webSocket?.send(message.toString()) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            false
        }
    }
    
    private fun generateRequestId(): String = UUID.randomUUID().toString()
    
    private fun getMediaDevicesJsonArray(): JSONArray {
        val devices = JSONArray()
        
        // Add audio input device
        val audioInput = JSONObject().apply {
            put("deviceId", "default")
            put("kind", "audioinput")
            put("label", "Default - Microphone")
            put("groupId", "default")
        }
        devices.put(audioInput)
        
        // Add audio output device
        val audioOutput = JSONObject().apply {
            put("deviceId", "default")
            put("kind", "audiooutput")
            put("label", "Default - Speaker")
            put("groupId", "default")
        }
        devices.put(audioOutput)
        
        return devices
    }
}