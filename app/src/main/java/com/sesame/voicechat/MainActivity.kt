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
        private const val INITIAL_ID_TOKEN = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImU4MWYwNTJhZWYwNDBhOTdjMzlkMjY1MzgxZGU2Y2I0MzRiYzM1ZjMiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiTG91bmVzIEIiLCJwaWN0dXJlIjoiaHR0cHM6Ly9saDMuZ29vZ2xldXNlcmNvbnRlbnQuY29tL2EvQUNnOG9jTFgyVlNPODMxeDJ4SWVwa0lPX3Q2ODNtY0xXdjQ3RlU2d3YzdWRTei03QmRaX2szWT1zOTYtYyIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9zZXNhbWUtYWktZGVtbyIsImF1ZCI6InNlc2FtZS1haS1kZW1vIiwiYXV0aF90aW1lIjoxNzU3MzIyODEzLCJ1c2VyX2lkIjoicHpiR05JV2FkS2MxdzRBejkwWU1iOWdHNXZGMiIsInN1YiI6InB6YkdOSVdhZEtjMXc0QXo5MFlNYjlnRzV2RjIiLCJpYXQiOjE3NTk1MDM1NTYsImV4cCI6MTc1OTUwNzE1NiwiZW1haWwiOiJsb3VuZXNiZW5hbGkuMkBnbWFpbC5jb20iLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwiZmlyZWJhc2UiOnsiaWRlbnRpdGllcyI6eyJnb29nbGUuY29tIjpbIjExODMxNjk2NjMwMTEzNDU1MjA1MyJdLCJlbWFpbCI6WyJsb3VuZXNiZW5hbGkuMkBnbWFpbC5jb20iXX0sInNpZ25faW5fcHJvdmlkZXIiOiJnb29nbGUuY29tIn19.G583LGaZ4qKZ_dDCyDV7MiGcdR3S16BGQtg7JWdv9VvIt37GgFpPQnQRKC531kltzdUL0Wejw_sUeIVEVmgRc07LpGuWWCmgdbYJkdZCPDtmG6-qBeb6tCS_JHJw3NfzwGDHPa_HJajnIj4stA2SESzs--57nm7716ydA3_gH2ydDjycG_5xLPTy4XeCC4LGyY4T04kb3C4fA0kgbKxoJ0_QLv8MEPyGmfonItpcCnOnYKo96b7XDzh5z5kZ2UvNibdSXg8exCHRC-k4-ncrUX64eEGq2LDHfpOMDF7G1cS6gMBasH-tCSXMSbulXr8v6Z6qvWNyOnpjmKoQhU9bTg"
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
        
        // Set character name
        characterName.text = selectedCharacter
        
        // Initialize UI state
        callDuration.text = "00:00"
        connectionStatus.text = "Connecting..."
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
            startCallTimer()
            setupAudio()
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
            
            // Start audio components
            if (audioPlayer?.startPlayback() == true && audioRecordManager?.startRecording() == true) {
                isConnected = true
                updateConnectionStatus("Connected", R.color.success_green)
                
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
}