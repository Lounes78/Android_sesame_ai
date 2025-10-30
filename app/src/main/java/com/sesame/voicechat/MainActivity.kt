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
        
        // Tokens are now loaded securely from configuration file
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
    private lateinit var sessionManager: SessionManager
    
    // State
    private var isConnected = false
    private var selectedCharacter = "Kira" // Default fallback
    private var selectedContactName = "Kira" // The actual contact name from intent
    private var selectedAudioRoute = AudioRoute.AUTO
    private var currentSession: SessionManager.SessionState? = null
    
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
        
        // Get contact name and language from intent (French-only)
        selectedContactName = intent.getStringExtra("CONTACT_KEY") ?: "Kira-FR" // Use full contact key with French
        selectedCharacter = intent.getStringExtra("CONTACT_NAME") ?: "Kira" // Display name without language
        
        initializeViews()
        setupAudioSystem()
        setupListeners()
        checkPermissions()
        setupTokenManager()
        setupAudioFileProcessor()
        setupSessionManager()
        
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
            Log.i(TAG, "Loading tokens from secure configuration...")
            
            val tokenPair = TokenConfig.loadTokens(this)
            if (tokenPair != null) {
                Log.i(TAG, "Storing tokens from configuration file...")
                tokenManager.storeTokens(tokenPair.idToken, tokenPair.refreshToken)
            } else {
                Log.e(TAG, "Failed to load tokens from configuration - app may not function properly")
                Toast.makeText(this, "Configuration error: Unable to load authentication tokens", Toast.LENGTH_LONG).show()
                return
            }
        }
        
        Log.d(TAG, tokenManager.getTokenInfo())
    }
    
    private fun setupAudioFileProcessor() {
        audioFileProcessor = AudioFileProcessor(this)
    }
    
    private fun setupSessionManager() {
        try {
            val application = getApplication() as SesameApplication
            sessionManager = application.getSessionManagerForContact(selectedContactName)
            Log.i(TAG, "[$selectedContactName] SessionManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SessionManager for $selectedContactName", e)
            // Fallback to default contact if specific contact fails
            val application = getApplication() as SesameApplication
            sessionManager = application.sessionManager // Uses default Kira
            Log.w(TAG, "Using fallback SessionManager")
        }
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
        
        updateConnectionStatus("Finding best session...", R.color.warning_orange)
        
        // Use coroutine to get session from pool
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Get the best available session for this contact from pool
                val sessionState = sessionManager.getBestAvailableSession(selectedContactName)
                
                if (sessionState == null) {
                    onWebSocketError("No available $selectedContactName sessions in pool. Please wait and try again.")
                    return@launch
                }
                
                currentSession = sessionState
                sesameWebSocket = sessionState.webSocket
                
                // Set character name
                characterName.text = selectedContactName
                
                val status = if (sessionState.isPromptComplete) {
                    "Session ready! Starting immediately..."
                } else {
                    "Session ${(sessionState.promptProgress * 100).toInt()}% ready..."
                }
                updateConnectionStatus(status, R.color.warning_orange)
                
                // Session is already connected, just set up our callbacks
                sessionState.webSocket.apply {
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
                
                // Immediately proceed to connected state
                onWebSocketConnected()
                
                Log.d(TAG, "[$selectedContactName] Using pre-warmed session: $selectedContactName (${if (sessionState.isPromptComplete) "ready" else "${(sessionState.promptProgress * 100).toInt()}% complete"})")
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                onWebSocketError("Connection error: ${e.message}")
            }
        }
    }
    
    private fun disconnect() {
        updateConnectionStatus("Disconnecting...", R.color.warning_orange)
        
        // Stop only the local audio components for this activity
        audioRecordManager?.stopRecording()
        audioPlayer?.stopPlayback()
        
        // DON'T reset global audio routing - let background sessions continue using audio system
        // resetAudioRouting() // Commented out to avoid affecting other sessions
        
        // Only remove the current session being used - others should continue
        currentSession?.let { session ->
            sessionManager.removeSession(session)
            Log.i(TAG, "Removed current session only - ${sessionManager.getPoolStatus()}")
        }
        
        // Clean up only local references - don't touch global systems
        audioRecordManager = null
        audioPlayer = null
        sesameWebSocket = null
        currentSession = null
        
        isConnected = false
        updateConnectionStatus("Disconnected", R.color.error_red)
        stopCallTimer()
        
        Log.i(TAG, "Local disconnect complete - background sessions unaffected")
    }
    
    private fun onWebSocketConnected() {
        runOnUiThread {
            Log.d(TAG, "Using pre-warmed session, setting up audio...")
            connectionStatus.text = "Connected"
            connectionStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            
            // Check if session prompt is complete
            currentSession?.let { session ->
                if (session.isPromptComplete) {
                    Log.i(TAG, "Session prompt already complete - ready for immediate chat!")
                    Toast.makeText(this, "Connected instantly! Session was pre-warmed.", Toast.LENGTH_SHORT).show()
                    
                    setupAudio()
                    startCallTimer()
                } else {
                    Log.i(TAG, "Session prompt ${(session.promptProgress * 100).toInt()}% complete - showing progress")
                    Toast.makeText(this, "Connected! Session finishing initialization...", Toast.LENGTH_SHORT).show()
                    
                    // Show initialization overlay and track progress
                    showInitializationOverlay()
                    trackSessionProgress()
                    
                    // Setup audio but don't start timer yet
                    setupAudio()
                }
            } ?: run {
                // Fallback if no session info
                setupAudio()
                startCallTimer()
            }
        }
    }
    
    private fun trackSessionProgress() {
        CoroutineScope(Dispatchers.Main).launch {
            while (isConnected) {
                // Check session progress directly from SessionManager to avoid reference issues
                val sessionProgress = sessionManager.getSessionProgress()
                
                if (sessionProgress != null) {
                    val (progress, isComplete) = sessionProgress
                    val progressPercent = (progress * 100).toInt()
                    
                    when {
                        isComplete -> {
                            updateInitializationStatus("Complete! Ready to chat.", 100)
                            
                            // NOW start AudioPlayer - session is complete
                            audioPlayer?.startPlayback()
                            Log.i(TAG, "AudioPlayer started - session complete, AI responses now enabled")
                            
                            delay(1000)
                            hideInitializationOverlay()
                            startCallTimer()
                            break
                        }
                        progressPercent > 0 -> {
                            updateInitializationStatus("Continuing initialization... $progressPercent%", progressPercent)
                        }
                        else -> {
                            updateInitializationStatus("Preparing session...", 0)
                        }
                    }
                } else {
                    updateInitializationStatus("Preparing session...", 0)
                }
                
                delay(500) // Update every 500ms
            }
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
            
            // Only start AudioPlayer if session is complete, otherwise wait
            currentSession?.let { session ->
                if (session.isPromptComplete) {
                    audioPlayer?.startPlayback()
                    Log.i(TAG, "AudioPlayer started - AI responses enabled (session complete)")
                } else {
                    Log.i(TAG, "AudioPlayer created but not started - waiting for session to complete")
                }
            } ?: run {
                // Fallback - start immediately if no session info
                audioPlayer?.startPlayback()
                Log.i(TAG, "AudioPlayer started - AI responses enabled (fallback)")
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
                    Log.e(TAG, "Audio recording error: $error")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Recording error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            // Start audio components immediately
            if (audioRecordManager?.startRecording() == true) {
                isConnected = true
                updateConnectionStatus("Connected", R.color.success_green)
                
                // Start audio processing thread
                startAudioProcessing()
                
                val routeInfo = when (selectedAudioRoute) {
                    AudioRoute.AUTO -> "using optimal audio routing"
                    else -> "using ${selectedAudioRoute.displayName}"
                }
                val modeInfo = if (isCarMode) " (Car optimized)" else " (Phone optimized)"
                val sessionStatus = currentSession?.let { session ->
                    if (session.isPromptComplete) " (instant connection)" else " (session ready)"
                } ?: ""
                Toast.makeText(this, "Voice chat started $routeInfo$modeInfo$sessionStatus! Speak naturally.", Toast.LENGTH_LONG).show()
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
        
        // Reset audio routing only when activity is truly destroyed
        resetAudioRouting()
        
        // Don't shutdown SessionManager when just finishing the call activity
        // SessionManager should keep running in the background for the entire app lifecycle
        // It will be managed by the Application class
        Log.i(TAG, "MainActivity destroyed - audio routing reset, SessionManager continues running")
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
    
    // Pre-recorded audio is now handled by SessionManager in background
    // This function is no longer needed but kept for potential future use
    private fun sendPreRecordedAudio() {
        Log.i(TAG, "Pre-recorded audio already sent by session pool - skipping")
        // Session pool has already handled the pre-recorded audio
        // Just log that we're using a pre-warmed session
        currentSession?.let { session ->
            val status = if (session.isPromptComplete) "complete" else "${(session.promptProgress * 100).toInt()}%"
            Log.i(TAG, "Using pre-warmed session - prompt $status")
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