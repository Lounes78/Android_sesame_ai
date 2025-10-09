package com.sesame.voicechat

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.concurrent.thread
import java.util.*
import java.util.TimerTask
import android.widget.FrameLayout
import android.widget.ImageView
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.View

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        
        // Initial tokens for first setup (will be managed by TokenManager)
        private const val INITIAL_ID_TOKEN = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImE1YTAwNWU5N2NiMWU0MjczMDBlNTJjZGQ1MGYwYjM2Y2Q4MDYyOWIiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiZ3B0IDEiLCJwaWN0dXJlIjoiaHR0cHM6Ly9saDMuZ29vZ2xldXNlcmNvbnRlbnQuY29tL2EvQUNnOG9jSjJLdXZELThIQU9NVHRMeW9hajFLcm5JQ3FWRWhLQ1p6UElfRThKSnlLTnhUVXpRPXM5Ni1jIiwiaXNzIjoiaHR0cHM6Ly9zZWN1cmV0b2tlbi5nb29nbGUuY29tL3Nlc2FtZS1haS1kZW1vIiwiYXVkIjoic2VzYW1lLWFpLWRlbW8iLCJhdXRoX3RpbWUiOjE3NTk1MDQ1MjQsInVzZXJfaWQiOiJFcHQyMVZBd0ZKaHVmZ0tHYmN0YmtEMGREY1gyIiwic3ViIjoiRXB0MjFWQXdGSmh1ZmdLR2JjdGJrRDBkRGNYMiIsImlhdCI6MTc1OTk5OTgyMCwiZXhwIjoxNzYwMDAzNDIwLCJlbWFpbCI6ImdwdDQ5NTkyQGdtYWlsLmNvbSIsImVtYWlsX3ZlcmlmaWVkIjp0cnVlLCJmaXJlYmFzZSI6eyJpZGVudGl0aWVzIjp7Imdvb2dsZS5jb20iOlsiMTAwOTc3MzcyODU0NDgxODIxNDAzIl0sImVtYWlsIjpbImdwdDQ5NTkyQGdtYWlsLmNvbSJdfSwic2lnbl9pbl9wcm92aWRlciI6Imdvb2dsZS5jb20ifX0.Jry2EpUvWP9rwH7w5u_4xX82Vkm7-thbSH07A2_cKSzNcvkg3a8eyGij6Wf9FzWMIFOE-zhdigGCMQ1234tDivk-aWSuodpgYyN6jnBUeAixUFmu6_sw1_OafBPOCL_QSUi9ovlBp1Go3B6ooC-dgLdzL4ZgxHqFcFiHBFcQCNzvU82-eHDauBzKTBu246-FxH4PoctiD0Y-W9SozmfJhHnuXQa0MXNq2Mrd3eBzhsNf01Rh9KCU2JyvTmQP4ZzhzKlxR8cZiYuBF_aW9G6nJYgptU0r4Vke1_S5yYqb_bMb_h6kjrP7C9BK6Dr63dVxJ4kpqS6cQtwDAxN0Lufrow"
        private const val INITIAL_REFRESH_TOKEN = "AMf-vBype8p6-ES7bY3E6hl4Bn1p5KzosYT4LBxjQz_CptDhSsQCFwAsiy8PVdbzwyWiENI1r9ti-Vx5MbGO8YrsWqi0SMEwV8QNyb0fdZzObEsgQKBQ6ICDIURhnjlksCibCdjkY-AKcZ8sJ-vtpGzaOWDBDMpdosutdUPUb_x6wR-2t6cjZfcQ24NmTxEw5PddGUa46X-Cr94GFRGyojoeV3VODfPh5pLF9C7TgMaJPeoTY3MYSoIfBZT8c9XT3cgPDH9ibWlJvgw8_6XnHdVYuIkgVXtmmU5hrQCFdvXiOrt-V3DNojWfqBT2PJHh3c2YdIUePt7RMun0lD3pt3iaUU5QmUpqWKPOSyTNWvGp_wjIi4OAShwVvDU-71FpyFjD13V5kNqeIgwz4Vt8OX7LUpp1p9yzytXMQ3PjnUY0jAa6hzxSrNg"
    }
    
    // UI Components for Call Screen
    private lateinit var characterName: TextView
    private lateinit var callDuration: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var voiceActivityIndicator: View
    private lateinit var pulseBackground: View
    private lateinit var muteButton: FrameLayout
    private lateinit var endCallButton: FrameLayout
    private lateinit var muteIcon: ImageView
    
    // Initialization Overlay Components
    private lateinit var initializationOverlay: View
    private lateinit var initializationTitle: TextView
    private lateinit var initializationMessage: TextView
    private lateinit var initializationProgress: android.widget.ProgressBar
    private lateinit var initializationProgressText: TextView
    
    // Call state
    private var callStartTime: Long = 0
    private var callTimer: Timer? = null
    private var isMuted = false
    private var pulseAnimator: AnimatorSet? = null
    
    // Core components
    private var sesameWebSocket: SesameWebSocket? = null
    private var audioRecordManager: com.sesame.voicechat.AudioManager? = null
    private var audioPlayer: AudioPlayer? = null
    private var systemAudioManager: AudioManager? = null
    private lateinit var tokenManager: TokenManager
    private lateinit var audioFileProcessor: AudioFileProcessor
    
    // State
    private var isConnected = false
    private var selectedCharacter = "Miles"
    private var selectedAudioRoute = AudioRoute.AUTO
    
    // Audio routing options
    enum class AudioRoute(val displayName: String) {
        AUTO("Auto (Recommended)"),
        SPEAKER("Phone Speaker"),
        EARPIECE("Phone Earpiece"),
        WIRED_HEADSET("Wired Headphones"),
        BLUETOOTH("Bluetooth Headset")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)
        
        // Get character from intent
        selectedCharacter = intent.getStringExtra("CHARACTER_NAME") ?: "Maya"
        
        initializeViews()
        setupAudioSystem()
        setupListeners()
        checkPermissions()
        setupTokenManager()
        setupAudioFileProcessor()
        
        // Auto-connect when call screen opens
        connect()
    }
    
    private fun initializeViews() {
        characterName = findViewById(R.id.characterName)
        callDuration = findViewById(R.id.callDuration)
        connectionStatus = findViewById(R.id.connectionStatus)
        voiceActivityIndicator = findViewById(R.id.voiceActivityIndicator)
        pulseBackground = findViewById(R.id.pulseBackground)
        muteButton = findViewById(R.id.muteButton)
        endCallButton = findViewById(R.id.endCallButton)
        muteIcon = findViewById(R.id.muteIcon)
        
        // Initialize initialization overlay components
        initializationOverlay = findViewById(R.id.initializationOverlay)
        initializationTitle = findViewById(R.id.initializationTitle)
        initializationMessage = findViewById(R.id.initializationMessage)
        initializationProgress = findViewById(R.id.initializationProgress)
        initializationProgressText = findViewById(R.id.initializationProgressText)
        
        // Set character name
        characterName.text = selectedCharacter
        
        // Initialize UI state
        callDuration.text = "00:00"
        connectionStatus.text = "Connecting..."
        
        // Hide initialization overlay initially
        initializationOverlay.visibility = View.GONE
    }
    
    private fun setupAudioSystem() {
        systemAudioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }
    
    private fun setupTokenManager() {
        tokenManager = TokenManager(this)
        
        // Store initial tokens if not already stored
        if (!tokenManager.hasStoredTokens()) {
            Log.i(TAG, "🔐 Storing initial tokens...")
            tokenManager.storeTokens(INITIAL_ID_TOKEN, INITIAL_REFRESH_TOKEN)
        }
        
        Log.d(TAG, tokenManager.getTokenInfo())
    }
    
    private fun setupAudioFileProcessor() {
        audioFileProcessor = AudioFileProcessor(this)
    }
    
    // Audio route spinner removed for new UI design
    
    private fun getAvailableAudioRoutes(): List<AudioRoute> {
        val routes = mutableListOf(AudioRoute.AUTO)
        
        // Always add basic phone audio
        routes.add(AudioRoute.SPEAKER)
        routes.add(AudioRoute.EARPIECE)
        
        // Check for wired headset
        if (systemAudioManager?.isWiredHeadsetOn == true) {
            routes.add(AudioRoute.WIRED_HEADSET)
        }
        
        // Check for Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = systemAudioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices?.forEach { device ->
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    if (!routes.contains(AudioRoute.BLUETOOTH)) {
                        routes.add(AudioRoute.BLUETOOTH)
                    }
                }
            }
        }
        
        return routes
    }
    
    private fun setupListeners() {
        muteButton.setOnClickListener {
            toggleMute()
        }
        
        endCallButton.setOnClickListener {
            disconnect()
            finish() // Return to contact selection screen
        }
    }
    
    private fun toggleMute() {
        isMuted = !isMuted
        
        if (isMuted) {
            muteIcon.setImageResource(R.drawable.ic_mic_off)
            muteIcon.setColorFilter(ContextCompat.getColor(this, R.color.error_red))
        } else {
            muteIcon.setImageResource(R.drawable.ic_mic)
            muteIcon.setColorFilter(ContextCompat.getColor(this, R.color.teal_accent))
        }
        
        // Mute/unmute the audio recording
        audioRecordManager?.let { manager ->
            // You can implement mute functionality here
            Log.d(TAG, if (isMuted) "Audio muted" else "Audio unmuted")
        }
    }
    
    private fun checkPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, 
                missingPermissions.toTypedArray(), 
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Audio permissions are required for voice chat", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun connect() {
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Audio permissions required", Toast.LENGTH_SHORT).show()
            checkPermissions()
            return
        }
        
        updateConnectionStatus("Connecting...", R.color.warning_orange)
        
        // Use coroutine to get valid token
        CoroutineScope(Dispatchers.Main).launch {
            try {
                updateConnectionStatus("Getting token...", R.color.warning_orange)
                
                // Get valid token (automatically refreshes if needed)
                val validToken = tokenManager.getValidIdToken()
                
                if (validToken == null) {
                    onWebSocketError("Failed to get valid authentication token")
                    return@launch
                }
                
                updateConnectionStatus("Connecting...", R.color.warning_orange)
                
                // Initialize WebSocket with valid token
                withContext(Dispatchers.IO) {
                    sesameWebSocket = SesameWebSocket(validToken, selectedCharacter).apply {
                        onConnectCallback = {
                            runOnUiThread { onWebSocketConnected() }
                        }
                        onDisconnectCallback = {
                            runOnUiThread { onWebSocketDisconnected() }
                        }
                        onErrorCallback = { error ->
                            runOnUiThread { onWebSocketError(error) }
                        }
                    }
                    
                    // Connect to WebSocket
                    if (sesameWebSocket?.connect() == true) {
                        Log.d(TAG, "WebSocket connection initiated with refreshed token")
                    } else {
                        runOnUiThread {
                            onWebSocketError("Failed to initiate connection")
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                onWebSocketError("Connection error: ${e.message}")
            }
        }
    }
    
    private fun disconnect() {
        updateConnectionStatus("Disconnecting...", R.color.warning_orange)
        
        // Stop audio components
        audioRecordManager?.stopRecording()
        audioPlayer?.stopPlayback()
        
        // Reset audio routing
        resetAudioRouting()
        
        // Disconnect WebSocket
        sesameWebSocket?.disconnect()
        
        // Clean up
        audioRecordManager = null
        audioPlayer = null
        sesameWebSocket = null
        
        isConnected = false
        updateConnectionStatus("Disconnected", R.color.error_red)
        stopCallTimer()
    }
    
    private fun onWebSocketConnected() {
        runOnUiThread {
            Log.d(TAG, "WebSocket connected, setting up audio...")
            connectionStatus.text = "Connected"
            connectionStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            // DON'T start timer yet - wait until message is sent
            setupAudio()
            
            // Automatically send the pre-recorded audio file after connection
            sendPreRecordedAudio()
        }
    }
    
    private fun startCallTimer() {
        callStartTime = System.currentTimeMillis()
        callTimer = Timer()
        callTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    updateCallDuration()
                }
            }
        }, 0, 1000) // Update every second
    }
    
    private fun updateCallDuration() {
        val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
        val minutes = elapsed / 60
        val seconds = elapsed % 60
        callDuration.text = String.format("%02d:%02d", minutes, seconds)
    }
    
    private fun stopCallTimer() {
        callTimer?.cancel()
        callTimer = null
    }
    
    private fun onWebSocketDisconnected() {
        runOnUiThread {
            if (isConnected) {
                disconnect()
            }
        }
    }
    
    private fun onWebSocketError(error: String) {
        runOnUiThread {
            Log.e(TAG, "WebSocket error: $error")
            Toast.makeText(this, "Connection error: $error", Toast.LENGTH_LONG).show()
            updateConnectionStatus("Connection failed", R.color.error_red)
        }
    }
    
    private fun setupAudio() {
        try {
            // Detect if running in car and apply optimizations
            val isCarMode = isRunningInCar()
            Log.i(TAG, "Setting up audio - Car mode: $isCarMode")
            
            // Apply optimal audio routing
            applyAudioRouting()
            
            // Initialize audio player with server sample rate
            val sampleRate = sesameWebSocket?.serverSampleRate ?: 24000
            audioPlayer = AudioPlayer(sampleRate).apply {
                onErrorCallback = { error ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Playback error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            // Initialize audio manager with car optimizations
            audioRecordManager = com.sesame.voicechat.AudioManager().apply {
                // Apply car optimizations if needed
                adjustForCarMode(isCarMode)
                
                // Use improved VAD sensitivity instead of debug mode
                setDebugMode(false)
                
                onAudioDataCallback = { audioData, hasVoice ->
                    // Send audio to WebSocket (reduced logging for performance)
                    if (hasVoice) {
                        sesameWebSocket?.sendAudioData(audioData)
                        runOnUiThread {
                            startVoicePulse()
                        }
                    } else {
                        // Send silence
                        val silentData = ByteArray(audioData.size) { 0 }
                        sesameWebSocket?.sendAudioData(silentData)
                        runOnUiThread {
                            stopVoicePulse()
                        }
                    }
                }
                onErrorCallback = { error ->
                    Log.e(TAG, "🎤 Audio recording error: $error")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Recording error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            // Start audio components but pause playback initially
            if (audioPlayer?.startPlayback() == true && audioRecordManager?.startRecording() == true) {
                isConnected = true
                updateConnectionStatus("Connected", R.color.success_green)
                
                // Pause audio playback during initialization to prevent AI responses
                audioPlayer?.clearQueue() // Clear any existing audio
                
                // Start audio processing thread
                startAudioProcessing()
                
                val routeInfo = when (selectedAudioRoute) {
                    AudioRoute.AUTO -> "using optimal audio routing"
                    else -> "using ${selectedAudioRoute.displayName}"
                }
                val modeInfo = if (isCarMode) " (Car optimized)" else " (Phone optimized)"
                Toast.makeText(this, "Voice chat started $routeInfo$modeInfo! Speak naturally.", Toast.LENGTH_LONG).show()
            } else {
                onWebSocketError("Failed to start audio components")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Audio setup error", e)
            onWebSocketError("Audio setup failed: ${e.message}")
        }
    }
    
    private fun applyAudioRouting() {
        systemAudioManager?.let { audioManager ->
            when (selectedAudioRoute) {
                AudioRoute.AUTO -> {
                    // Auto mode: Choose best available route
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                    
                    // Prefer wired headset > Bluetooth > earpiece > speaker
                    when {
                        audioManager.isWiredHeadsetOn -> {
                            Log.d(TAG, "Auto: Using wired headset")
                        }
                        audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn -> {
                            Log.d(TAG, "Auto: Using Bluetooth")
                            audioManager.startBluetoothSco()
                        }
                        else -> {
                            Log.d(TAG, "Auto: Using earpiece")
                            audioManager.isSpeakerphoneOn = false
                        }
                    }
                }
                
                AudioRoute.SPEAKER -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = true
                    Log.d(TAG, "Forced speaker mode")
                }
                
                AudioRoute.EARPIECE -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                    Log.d(TAG, "Forced earpiece mode")
                }
                
                AudioRoute.WIRED_HEADSET -> {
                    if (audioManager.isWiredHeadsetOn) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        audioManager.isSpeakerphoneOn = false
                        Log.d(TAG, "Forced wired headset mode")
                    } else {
                        Toast.makeText(this, "Wired headset not connected, using auto mode", Toast.LENGTH_SHORT).show()
                        selectedAudioRoute = AudioRoute.AUTO
                        applyAudioRouting()
                    }
                }
                
                AudioRoute.BLUETOOTH -> {
                    if (audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        Log.d(TAG, "Forced Bluetooth mode")
                    } else {
                        Toast.makeText(this, "Bluetooth not connected, using auto mode", Toast.LENGTH_SHORT).show()
                        selectedAudioRoute = AudioRoute.AUTO
                        applyAudioRouting()
                    }
                }
            }
        }
    }
    
    private fun resetAudioRouting() {
        systemAudioManager?.let { audioManager ->
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
    }
    
    private fun updateAudioDeviceInfo() {
        val info = StringBuilder()
        systemAudioManager?.let { audioManager ->
            info.append("Audio Status:\n")
            info.append("• Wired Headset: ${if (audioManager.isWiredHeadsetOn) "Connected" else "Not connected"}\n")
            info.append("• Bluetooth A2DP: ${if (audioManager.isBluetoothA2dpOn) "Connected" else "Not connected"}\n")
            info.append("• Bluetooth SCO: ${if (audioManager.isBluetoothScoOn) "Active" else "Inactive"}\n")
            info.append("• Speaker: ${if (audioManager.isSpeakerphoneOn) "On" else "Off"}\n")
            info.append("• Mode: ${getAudioModeString(audioManager.mode)}")
        }
        
        // Audio info text removed for new UI design
        Log.d(TAG, info.toString())
    }
    
    private fun getAudioModeString(mode: Int): String {
        return when (mode) {
            AudioManager.MODE_NORMAL -> "Normal"
            AudioManager.MODE_RINGTONE -> "Ringtone"
            AudioManager.MODE_IN_CALL -> "In Call"
            AudioManager.MODE_IN_COMMUNICATION -> "Communication (Optimal)"
            else -> "Unknown ($mode)"
        }
    }
    
    private fun startAudioProcessing() {
        thread {
            var lastBufferLogTime = 0L
            
            while (isConnected && sesameWebSocket?.isConnected() == true) {
                try {
                    // Get audio from WebSocket and play it
                    val audioChunk = sesameWebSocket?.getNextAudioChunk()
                    if (audioChunk != null) {
                        audioPlayer?.queueAudioData(audioChunk)
                    }
                    
                    // Monitor buffer health periodically
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBufferLogTime > 10000) { // Every 10 seconds
                        logBufferHealth()
                        lastBufferLogTime = currentTime
                    }
                    
                    // Optimized delay for car vs phone
                    val delayMs = if (isRunningInCar()) 10L else 5L
                    Thread.sleep(delayMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Audio processing error", e)
                    break
                }
            }
        }
    }
    
    private fun updateConnectionStatus(status: String, colorRes: Int) {
        runOnUiThread {
            connectionStatus.text = status
            connectionStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        }
    }
    
    private fun startVoicePulse() {
        // Stop any existing animation
        stopVoicePulse()
        
        // Create scale animations for the voice activity indicator
        val scaleX = ObjectAnimator.ofFloat(voiceActivityIndicator, "scaleX", 1.0f, 1.2f, 1.0f).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        
        val scaleY = ObjectAnimator.ofFloat(voiceActivityIndicator, "scaleY", 1.0f, 1.2f, 1.0f).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        
        // Create pulse animation for background
        val backgroundPulse = ObjectAnimator.ofFloat(pulseBackground, "alpha", 0.3f, 0.8f, 0.3f).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        
        // Create animator set
        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, backgroundPulse)
        }
        
        pulseAnimator?.start()
    }
    
    private fun stopVoicePulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        
        // Reset to default state
        voiceActivityIndicator.scaleX = 1.0f
        voiceActivityIndicator.scaleY = 1.0f
        pulseBackground.alpha = 0.3f
    }
    
    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
    
    override fun onPause() {
        super.onPause()
        if (isConnected) {
            // Optionally pause audio processing here
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Resume audio processing if needed
    }
    
    private fun isRunningInCar(): Boolean {
        return packageManager.hasSystemFeature("android.hardware.type.automotive")
    }
    
    private fun logBufferHealth() {
        audioPlayer?.let { player ->
            val bufferHealth = player.getBufferHealth()
            Log.i(TAG, "Buffer Health: $bufferHealth")
        }
        
        audioRecordManager?.let { manager ->
            val captureMetrics = manager.getCaptureMetrics()
            Log.i(TAG, "Capture Metrics: $captureMetrics")
        }
    }
    
    private fun sendPreRecordedAudio() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "🎵 Loading pre-recorded audio file...")
                
                // Show initialization overlay
                runOnUiThread {
                    showInitializationOverlay()
                    updateInitializationStatus("Loading audio file...", 0)
                }
                
                val audioChunks = audioFileProcessor.loadWavFile("openai-fm-coral-eternal-optimist.wav")
                
                if (audioChunks != null && audioChunks.isNotEmpty()) {
                    Log.i(TAG, "🎵 Pausing microphone and sending ${audioChunks.size} audio chunks...")
                    
                    // PAUSE microphone recording to prevent interference
                    val wasRecording = audioRecordManager?.isRecording() ?: false
                    if (wasRecording) {
                        Log.i(TAG, "🎤 Pausing microphone recording during pre-recorded audio")
                        audioRecordManager?.stopRecording()
                    }
                    
                    runOnUiThread {
                        updateInitializationStatus("Sending your message to AI...", 10)
                    }
                    
                    // Send audio chunks directly through WebSocket (bypassing microphone)
                    for (i in audioChunks.indices) {
                        val chunk = audioChunks[i]
                        
                        // Check if WebSocket is still active
                        if (!isConnected || sesameWebSocket?.isConnected() != true) {
                            Log.w(TAG, "WebSocket disconnected, stopping audio send")
                            break
                        }
                        
                        // Send directly through WebSocket with voice activity indication
                        val success = sesameWebSocket?.sendAudioData(chunk) ?: false
                        
                        if (!success) {
                            Log.e(TAG, "❌ Failed to send audio chunk $i")
                            break
                        }
                        
                        // Add small delay between chunks to simulate real-time audio streaming
                        // Each chunk represents ~64ms of audio (1024 samples at 16kHz)
                        delay(64) // 64ms delay between chunks
                        
                        // Update progress more frequently for smoother animation
                        val progress = 10 + ((i + 1) * 85) / audioChunks.size // 10% to 95%
                        runOnUiThread {
                            updateInitializationStatus("Transmitting audio... (${i + 1}/${audioChunks.size})", progress)
                        }
                    }
                    
                    // Send a final silence chunk to signal end of speech
                    runOnUiThread {
                        updateInitializationStatus("Finalizing transmission...", 95)
                    }
                    
                    val silenceChunk = ByteArray(2048) { 0 } // Silent chunk
                    sesameWebSocket?.sendAudioData(silenceChunk)
                    
                    // Wait a moment before resuming microphone
                    delay(500) // 500ms pause to ensure AI processes the end of speech
                    
                    // RESUME microphone recording
                    if (wasRecording && isConnected) {
                        Log.i(TAG, "🎤 Resuming microphone recording after pre-recorded audio")
                        audioRecordManager?.startRecording()
                    }
                    
                    Log.i(TAG, "✅ Pre-recorded audio sent successfully!")
                    runOnUiThread {
                        updateInitializationStatus("Complete! Ready to chat.", 100)
                        // Small delay to show completion before hiding
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(1000)
                            hideInitializationOverlay()
                        }
                    }
                    
                } else {
                    Log.e(TAG, "❌ Failed to load audio file")
                    runOnUiThread {
                        hideInitializationOverlay()
                        Toast.makeText(this@MainActivity, "Failed to load audio file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending pre-recorded audio", e)
                
                // Ensure microphone is resumed even if there's an error
                try {
                    if (isConnected && audioRecordManager?.isRecording() != true) {
                        Log.i(TAG, "🎤 Resuming microphone after error")
                        audioRecordManager?.startRecording()
                    }
                } catch (resumeError: Exception) {
                    Log.e(TAG, "Error resuming microphone", resumeError)
                }
                
                runOnUiThread {
                    hideInitializationOverlay()
                    Toast.makeText(this@MainActivity, "Error sending audio: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showInitializationOverlay() {
        initializationOverlay.visibility = View.VISIBLE
        initializationOverlay.alpha = 0f
        initializationOverlay.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }
    
    private fun hideInitializationOverlay() {
        initializationOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                initializationOverlay.visibility = View.GONE
            }
            .start()
    }
    
    private fun updateInitializationStatus(message: String, progress: Int) {
        initializationMessage.text = message
        initializationProgress.progress = progress
        initializationProgressText.text = "$progress%"
        
        // Update title based on progress
        when {
            progress < 10 -> initializationTitle.text = "Initializing..."
            progress < 95 -> initializationTitle.text = "Cooking..."
            else -> initializationTitle.text = "Almost Ready..."
        }
    }
}