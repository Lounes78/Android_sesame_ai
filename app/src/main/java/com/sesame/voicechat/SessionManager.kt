package com.sesame.voicechat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SessionManager private constructor(
    private val context: Context,
    private val contactName: String,
    private val poolSize: Int
) {
    
    companion object {
        private const val TAG = "SessionManager"
        private val INSTANCES = mutableMapOf<String, SessionManager>()
        
        fun getInstance(context: Context, contactName: String, poolSize: Int = 8): SessionManager {
            val key = "$contactName-$poolSize"
            return INSTANCES[key] ?: synchronized(this) {
                INSTANCES[key] ?: SessionManager(context.applicationContext, contactName, poolSize).also {
                    INSTANCES[key] = it
                }
            }
        }
        
        fun getAllInstances(): Map<String, SessionManager> = INSTANCES.toMap()
        
        fun clearAllInstances() {
            synchronized(this) {
                INSTANCES.values.forEach { it.shutdown() }
                INSTANCES.clear()
            }
        }
        
        // Map app contact names to backend character names
        private fun getBackendCharacter(contactName: String): String {
            val character = extractCharacterFromKey(contactName)
            return when (character.lowercase()) {
                "kira" -> "Maya"
                "hugo" -> "Maya"  // Both contacts use Maya backend
                else -> character // Fallback to original name
            }
        }
        
        // Extract character from contact key (e.g., "Kira-EN" -> "Kira" or "Kira" -> "Kira")
        private fun extractCharacterFromKey(contactKey: String): String {
            return contactKey.split("-").firstOrNull() ?: contactKey
        }
        
        // Extract language from contact key (e.g., "Kira-EN" -> "EN" or "Kira" -> "EN")
        private fun extractLanguageFromKey(contactKey: String): String {
            return contactKey.split("-").getOrNull(1) ?: "EN" // Default to English
        }
    }
    
    data class SessionState(
        var webSocket: SesameWebSocket,
        val character: String,
        val contactKey: String, // Add contactKey to distinguish language-specific sessions
        val language: String, // SAFETY BELT: Store actual language for validation
        val createdAt: Long,
        var isPromptComplete: Boolean = false,
        var promptProgress: Float = 0f,
        var isAvailable: Boolean = true,
        var isInUse: Boolean = false,
        var job: Job
    )
    
    data class SessionInfo(
        val progress: Float,
        val isComplete: Boolean,
        val isConnected: Boolean
    )
    
    private val sessionPool = ConcurrentLinkedQueue<SessionState>()
    private val isRunning = AtomicBoolean(false)
    private val isInitialPoolCreation = AtomicBoolean(true)
    private val pendingCreations = AtomicInteger(0) // Track sessions being created to prevent overshooting
    private val sessionCounter = AtomicInteger(0) // Contact-specific sequential counter for session numbers
    private var lastScheduledStartMs = 0L // Track actual audio start times to prevent timing drift
    private val cycleBufferCreated = AtomicBoolean(false) // Track if we've created buffer sessions for current cycle
    private val creationInProgress = AtomicBoolean(false) // Prevent multiple concurrent session creations
    private lateinit var tokenManager: TokenManager
    private lateinit var audioFileProcessor: AudioFileProcessor
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    fun initialize(tokenManager: TokenManager) {
        this.tokenManager = tokenManager
        this.audioFileProcessor = AudioFileProcessor(context)
        
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "[$contactName] Initializing session pool with $poolSize sessions")
            startPoolMaintenance()
        }
    }
    
    private fun startPoolMaintenance() {
        scope.launch {
            // Start session creation timer - create one session every intervalSeconds
            val intervalMs = (60L * 1000L) / poolSize // Dynamic interval based on pool size
            
            while (isRunning.get()) {
                try {
                    // ATOMIC pool size check - prevent all race conditions
                    val shouldCreateSession = synchronized(this@SessionManager) {
                        val currentSessions = sessionPool.size
                        val pendingSessions = pendingCreations.get()
                        val totalPlanned = currentSessions + pendingSessions
                        
                        when {
                            totalPlanned < poolSize && !creationInProgress.get() -> {
                                Log.i(TAG, "[$contactName] Creating session to maintain pool (${currentSessions}+${pendingSessions}/${poolSize})")
                                true
                            }
                            totalPlanned >= poolSize -> {
                                if (totalPlanned > poolSize) {
                                    Log.w(TAG, "[$contactName] Pool overshooting detected (${totalPlanned}/${poolSize}) - skipping creation")
                                }
                                false
                            }
                            else -> false
                        }
                    }
                    
                    // Create session outside synchronized block
                    if (shouldCreateSession) {
                        createSessionWithTimer()
                    } else {
                        // Clean up any dead sessions to free up space
                        cleanupDeadSessions()
                    }
                    
                    // Wait for next interval before creating another session
                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "[$contactName] Pool maintenance error", e)
                    delay(10000) // Wait longer on error
                }
            }
        }
    }
    
    // REMOVED: Buffer session logic was causing overshooting
    // Pool maintenance now handles all session creation properly
    
    private suspend fun createSessionWithTimer() {
        // Prevent multiple concurrent session creations
        if (!creationInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "[$contactName] Session creation already in progress - skipping")
            return
        }
        
        try {
            // ATOMIC check and increment
            val shouldProceed = synchronized(this@SessionManager) {
                val currentSessions = sessionPool.size
                val pendingSessions = pendingCreations.get()
                if (currentSessions + pendingSessions < poolSize) {
                    pendingCreations.incrementAndGet()
                    true
                } else {
                    Log.w(TAG, "[$contactName] Pool size check failed during creation (${currentSessions}+${pendingSessions}/${poolSize}) - aborting")
                    false
                }
            }
            
            if (!shouldProceed) return
            
            val validToken = tokenManager.getValidIdToken()
            if (validToken == null) {
                Log.e(TAG, "[$contactName] Cannot create session: no valid token")
                return
            }
            
            val character = getBackendCharacter(contactName)
            val sessionIndex = sessionCounter.incrementAndGet()
            
            Log.i(TAG, "[$contactName] Starting session #${sessionIndex} - WebSocket in 2s, audio immediately after")
            
            // Wait longer between connections to prevent rate limiting, with more jitter
            val jitter = (1000..3000).random() // 1-3 second jitter
            val baseDelay = 5000L // 5 second base delay
            delay(baseDelay + jitter)
            
            Log.i(TAG, "[$contactName] Starting WebSocket connection for session #${sessionIndex} after ${(baseDelay + jitter)/1000}s delay")
            
            scope.launch {
                try {
                
                    Log.i(TAG, "[$contactName] Creating WebSocket for session #${sessionIndex} ($character)")
                    val webSocket = SesameWebSocket(validToken, character, "RP-Android").apply {
                        onConnectCallback = {
                            Log.d(TAG, "[$contactName] Background session #${sessionIndex} connected for $character")
                        }
                        onDisconnectCallback = {
                            Log.d(TAG, "[$contactName] Background session #${sessionIndex} disconnected for $character")
                        }
                        onErrorCallback = { error ->
                            Log.e(TAG, "[$contactName] Background session #${sessionIndex} error for $character: $error")
                        }
                    }
                    
                    if (webSocket.connect()) {
                        // Wait for connection with longer timeout
                        var attempts = 0
                        val maxAttempts = 200 // 20 second timeout (200 × 100ms) - increased for concurrent connections
                        while (!webSocket.isConnected() && attempts < maxAttempts) {
                            delay(100)
                            attempts++
                            
                            // Log progress every 5 seconds
                            if (attempts % 50 == 0) {
                                Log.i(TAG, "[$contactName] Session #${sessionIndex} still connecting... ${attempts * 100}ms elapsed")
                            }
                        }
                        
                        if (webSocket.isConnected()) {
                            Log.i(TAG, "[$contactName] Session #${sessionIndex} connected after ${attempts * 100}ms")
                            
                            // Create and add session to pool immediately after connection
                            val actualLanguage = extractLanguageFromKey(contactName)
                            val sessionState = SessionState(
                                webSocket = webSocket,
                                character = character,
                                contactKey = contactName, // Store the full contact key (e.g., "Kira-EN", "Kira-FR")
                                language = actualLanguage, // SAFETY BELT: Store the actual language
                                createdAt = System.currentTimeMillis(),
                                job = coroutineContext[Job]!!
                            )
                            
                            sessionPool.offer(sessionState)
                            Log.i(TAG, "[$contactName] Added session #${sessionIndex} for $character to pool (${sessionPool.size}/$poolSize)")
                            
                            // Start audio immediately
                            sendPreRecordedAudioToSession(webSocket, character, sessionIndex)
                        } else {
                            // Timeout - clean up the WebSocket and log detailed info
                            Log.e(TAG, "[$contactName] Session #${sessionIndex} WebSocket timed out after 10 seconds - cleaning up")
                            webSocket.disconnect()
                        }
                    } else {
                        Log.e(TAG, "[$contactName] Failed to create WebSocket for session #${sessionIndex}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$contactName] Error creating timer-based session #${sessionIndex}", e)
                } finally {
                    pendingCreations.decrementAndGet()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$contactName] Error in createSessionWithTimer", e)
            pendingCreations.decrementAndGet()
        } finally {
            creationInProgress.set(false)
        }
    }
    
    // Simplified maintenance - just clean up dead sessions
    private suspend fun cleanupDeadSessions() {
        val currentTime = System.currentTimeMillis()
        val GRACE_PERIOD_MS = 15000L // 15 seconds grace period for new sessions
        
        synchronized(this@SessionManager) {
            val iterator = sessionPool.iterator()
            var removed = 0
            while (iterator.hasNext()) {
                val session = iterator.next()
                val sessionAge = currentTime - session.createdAt
                
                // Remove sessions that are dead, old and unused, or marked as unavailable
                val isOldEnough = sessionAge > GRACE_PERIOD_MS
                val isActuallyDead = !session.webSocket.isConnected() || session.job.isCancelled
                val isMarkedForRemoval = !session.isAvailable && !session.isInUse
                
                if ((isOldEnough && isActuallyDead) || isMarkedForRemoval) {
                    Log.d(TAG, "[$contactName] Removing dead/unused session for ${session.character} (age: ${sessionAge/1000}s)")
                    session.job.cancel()
                    session.webSocket.disconnect()
                    iterator.remove()
                    removed++
                }
            }
            
            if (removed > 0) {
                Log.i(TAG, "[$contactName] Cleaned up $removed sessions - pool status: ${sessionPool.size}/$poolSize")
            }
        }
    }
    
    // This function is no longer needed with the timer-based approach
    
    private suspend fun sendPreRecordedAudioToSession(webSocket: SesameWebSocket, character: String, sessionNumber: Int) {
        try {
            Log.i(TAG, "Sending pre-recorded audio to background session #$sessionNumber ($character)")
            
            // Extract character and language from contact name for language-specific prompt selection
            val characterName = extractCharacterFromKey(contactName).lowercase()
            val language = extractLanguageFromKey(contactName).lowercase()
            
            // Use character + language specific audio files matching your naming convention
            val audioFileName = when (characterName) {
                "kira" -> when (language) {
                    "fr" -> "kira_fr.wav"
                    else -> "kira_en.wav" // Default to English
                }
                "hugo" -> when (language) {
                    "fr" -> "hugo_fr.wav"
                    else -> "hugo_en.wav" // Default to English
                }
                else -> "kira_en.wav" // Fallback to English Kira
            }
            
            Log.i(TAG, "[$contactName] Using prompt file: $audioFileName (character: $characterName, language: $language)")
            
            val audioChunks = audioFileProcessor.loadWavFile(audioFileName)
            if (audioChunks != null && audioChunks.isNotEmpty()) {
                
                // Find the session state to update progress
                val sessionState = sessionPool.find { it.webSocket == webSocket }
                
                // Send audio chunks (simplified like working version)
                for (i in audioChunks.indices) {
                    val chunk = audioChunks[i]
                    
                    if (!webSocket.isConnected()) {
                        Log.w(TAG, "WebSocket disconnected during audio send ($character)")
                        break
                    }
                    
                    val success = webSocket.sendAudioData(chunk)
                    if (!success) {
                        Log.e(TAG, "Failed to send audio chunk $i to $character")
                        break
                    }
                    
                    // Update progress
                    sessionState?.let { state ->
                        state.promptProgress = (i + 1).toFloat() / audioChunks.size
                    }
                    
                    // Correct timing for processed audio: All audio is converted to 16kHz mono by AudioFileProcessor
                    // 2048 bytes = 1024 samples at 16kHz = 64ms per chunk for real-time playback
                    delay(64)
                }
                
                // Send silence to signal end of speech
                val silenceChunk = ByteArray(2048) { 0 }
                webSocket.sendAudioData(silenceChunk)
                
                // Mark session as prompt complete
                sessionState?.let { state ->
                    state.promptProgress = 1.0f
                    state.isPromptComplete = true
                    state.isAvailable = true
                    Log.i(TAG, "Session #$sessionNumber ($character) prompt complete - session is now done")
                }
                
                // Wait for AI response processing
                delay(500)
                
                // Session is now "done" - schedule it for replacement (like working version)
                sessionState?.let { state ->
                    scope.launch {
                        delay(5000) // Keep the completed session available for 5 seconds for immediate use
                        
                        // Check if session is still not in use, then replace it
                        if (!state.isInUse) {
                            Log.i(TAG, "Session #$sessionNumber ($character) done and unused - replacing with new session")
                            
                            // Remove the completed session
                            state.job.cancel()
                            state.webSocket.disconnect()
                            sessionPool.remove(state)
                            
                            // Create a new session to maintain pool size
                            pendingCreations.incrementAndGet()
                            try {
                                createSessionWithTimer()
                            } finally {
                                pendingCreations.decrementAndGet()
                            }
                        } else {
                            Log.i(TAG, "Session #$sessionNumber ($character) is in use - keeping it")
                        }
                    }
                }
                
            } else {
                Log.e(TAG, "Failed to load audio file for background session #$sessionNumber ($character)")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending pre-recorded audio to session #$sessionNumber ($character)", e)
        }
    }
    
    /**
     * Get the best available session for user connection
     * Prioritizes sessions with completed prompts or highest progress
     * Currently only supports Maya sessions
     */
    fun getBestAvailableSession(preferredCharacter: String? = null): SessionState? {
        val backendCharacter = getBackendCharacter(contactName)
        val requestedLanguage = extractLanguageFromKey(contactName)
        
        // SAFETY BELT: Filter by multiple criteria including language validation
        val availableSessions = sessionPool.filter {
            it.isAvailable && !it.isInUse && it.webSocket.isConnected() &&
            it.character == backendCharacter && it.contactKey == contactName && // Match exact contact key including language
            it.language == requestedLanguage // SAFETY BELT: Refuse sessions with wrong language
        }
        
        // Additional logging to catch cross-contamination
        val wrongLanguageSessions = sessionPool.filter {
            it.isAvailable && !it.isInUse && it.webSocket.isConnected() &&
            it.character == backendCharacter && it.language != requestedLanguage
        }
        
        if (wrongLanguageSessions.isNotEmpty()) {
            Log.w(TAG, "[$contactName] SAFETY BELT: Found ${wrongLanguageSessions.size} sessions with wrong language:")
            wrongLanguageSessions.forEach { session ->
                Log.w(TAG, "[$contactName] Wrong session: contactKey=${session.contactKey}, storedLang=${session.language}, requestedLang=$requestedLanguage")
            }
        }
        
        if (availableSessions.isEmpty()) {
            Log.w(TAG, "[$contactName] No available sessions in pool")
            return null
        }
        
        // Find session with highest progress (closest to completion)
        val bestSession = availableSessions.maxByOrNull { session ->
            if (session.isPromptComplete) 100f else session.promptProgress
        }
        
        bestSession?.let { session ->
            session.isInUse = true
            val status = if (session.isPromptComplete) "complete" else "${(session.promptProgress * 100).toInt()}%"
            Log.i(TAG, "[$contactName] Using session (prompt $status)")
        }
        
        return bestSession
    }
    
    /**
     * Return a session to the pool after use - session can be reused
     */
    fun returnSession(sessionState: SessionState) {
        sessionState.isInUse = false
        Log.d(TAG, "Returned session ${sessionState.character} to pool - can be reused")
        // No replacement needed - session continues to exist and can be reused
    }
    
    /**
     * Remove a session from the pool only when it's truly done (WebSocket failed, etc.)
     */
    fun removeSession(sessionState: SessionState) {
        synchronized(this@SessionManager) {
            sessionState.job.cancel()
            sessionState.webSocket.disconnect()
            sessionPool.remove(sessionState)
            Log.d(TAG, "Removed session ${sessionState.character} from pool - pool maintenance will create replacement")
            // DON'T create replacement here - let pool maintenance handle it
        }
    }
    
    fun getPoolStatus(): String {
        val total = sessionPool.size
        val available = sessionPool.count { it.isAvailable && !it.isInUse }
        val complete = sessionPool.count { it.isPromptComplete }
        return "[$contactName] Pool: $total total, $available available, $complete ready"
    }
    
    /**
     * Get session progress info without claiming the session
     * Returns progress of the most advanced session
     */
    fun getSessionProgress(): Pair<Float, Boolean>? {
        val backendCharacter = getBackendCharacter(contactName)
        val contactSessions = sessionPool.filter {
            it.character == backendCharacter && it.contactKey == contactName && it.webSocket.isConnected()
        }
        
        if (contactSessions.isEmpty()) {
            return null
        }
        
        // Find the session with highest progress, prioritizing completed ones
        val bestSession = contactSessions.maxWithOrNull(compareBy<SessionState> {
            if (it.isPromptComplete) 1000f else it.promptProgress
        }.thenBy { it.promptProgress })
        
        return bestSession?.let {
            Log.d(TAG, "[$contactName] Session progress check: ${it.promptProgress * 100}% complete=${it.isPromptComplete}")
            Pair(it.promptProgress, it.isPromptComplete)
        }
    }
    
    /**
     * Get progress info for all sessions (for UI display)
     */
    fun getAllSessionsProgress(): List<SessionInfo> {
        val backendCharacter = getBackendCharacter(contactName)
        val contactSessions = sessionPool.filter {
            it.character == backendCharacter && it.contactKey == contactName
        }.sortedBy { it.createdAt } // Sort by creation time to maintain consistent order
        
        return contactSessions.map { session ->
            SessionInfo(
                progress = session.promptProgress,
                isComplete = session.isPromptComplete,
                isConnected = session.webSocket.isConnected()
            )
        }
    }
    
    fun shutdown() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "[$contactName] Shutting down session pool")
            
            // Cancel all sessions
            sessionPool.forEach { session ->
                session.job.cancel()
                session.webSocket.disconnect()
            }
            sessionPool.clear()
            
            // Cancel scope
            scope.cancel()
        }
    }
}
