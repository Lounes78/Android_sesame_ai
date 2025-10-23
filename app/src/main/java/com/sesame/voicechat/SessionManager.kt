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
        
        // Map app contact names to backend character names
        private fun getBackendCharacter(contactName: String): String {
            return when (contactName.lowercase()) {
                "kira" -> "Maya"
                "hugo" -> "Maya"  // Both contacts use Maya backend
                else -> contactName // Fallback to original name
            }
        }
    }
    
    data class SessionState(
        var webSocket: SesameWebSocket,
        val character: String,
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
                    // Check if we need more sessions for normal pool
                    val currentSessions = sessionPool.size
                    val pendingSessions = pendingCreations.get()
                    val totalPlanned = currentSessions + pendingSessions
                    
                    if (totalPlanned < poolSize && !creationInProgress.get()) {
                        Log.i(TAG, "[$contactName] Creating session to maintain pool (${currentSessions}+${pendingSessions}/${poolSize})")
                        createSessionWithTimer()
                    } else if (totalPlanned > poolSize) {
                        Log.w(TAG, "[$contactName] Pool overshooting detected (${totalPlanned}/${poolSize}) - skipping creation")
                    }
                    
                    // Check if we need to create buffer sessions for end-of-cycle gap
                    checkAndCreateCycleBuffer()
                    
                    // Wait for next interval before creating another session
                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "[$contactName] Pool maintenance error", e)
                    delay(10000) // Wait longer on error
                }
            }
        }
    }
    
    private suspend fun checkAndCreateCycleBuffer() {
        // Find sessions that are 75% or more complete
        val nearCompletionSessions = sessionPool.count { session ->
            session.promptProgress >= 0.75f && !session.isPromptComplete
        }
        
        // If we have sessions near completion and haven't created buffer yet
        if (nearCompletionSessions >= 3 && !cycleBufferCreated.get()) {
            val bufferSize = 2 // Create 2 buffer sessions to bridge the gap
            Log.i(TAG, "[$contactName] Creating $bufferSize buffer sessions for end-of-cycle gap (${nearCompletionSessions} sessions at 75%+)")
            
            cycleBufferCreated.set(true)
            
            // Create buffer sessions with slight stagger
            repeat(bufferSize) { index ->
                scope.launch {
                    delay(index * 1000L) // 1 second stagger between buffer sessions
                    createSessionWithTimer()
                }
            }
        }
        
        // Reset buffer flag when cycle restarts (all sessions either complete or restarted)
        val completedSessions = sessionPool.count { it.isPromptComplete }
        val totalSessions = sessionPool.size
        if (completedSessions >= poolSize / 2 && cycleBufferCreated.get()) {
            Log.d(TAG, "[$contactName] Cycle restarting - resetting buffer flag (${completedSessions}/${totalSessions} complete)")
            cycleBufferCreated.set(false)
        }
    }
    
    private suspend fun createSessionWithTimer() {
        // Prevent multiple concurrent session creations
        if (!creationInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "[$contactName] Session creation already in progress - skipping")
            return
        }
        
        try {
            pendingCreations.incrementAndGet()
            
            val validToken = tokenManager.getValidIdToken()
            if (validToken == null) {
                Log.e(TAG, "[$contactName] Cannot create session: no valid token")
                return
            }
            
            val character = getBackendCharacter(contactName)
            val sessionIndex = sessionCounter.incrementAndGet()
            
            Log.i(TAG, "[$contactName] Starting session #${sessionIndex} - WebSocket in 2s, audio immediately after")
            
            // Wait 2 seconds before creating WebSocket with jitter to prevent thundering herd
            val jitter = (0..500).random()
            delay(2000L + jitter)
            
            // Double-check we still need this session before creating
            val currentSessions = sessionPool.size
            if (currentSessions >= poolSize) {
                Log.w(TAG, "[$contactName] Pool full during creation (${currentSessions}/${poolSize}) - aborting session #${sessionIndex}")
                return
            }
            
            scope.launch {
                try {
                
                    // Create WebSocket
                    Log.i(TAG, "[$contactName] Creating WebSocket for session #${sessionIndex} ($character)")
                    val webSocket = SesameWebSocket(validToken, character).apply {
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
                        // Wait for connection with more realistic timeout
                        var attempts = 0
                        val maxAttempts = 100 // 10 second timeout (100 × 100ms) - increased for stability
                        while (!webSocket.isConnected() && attempts < maxAttempts) {
                            delay(100)
                            attempts++
                        }
                        
                        if (webSocket.isConnected()) {
                            Log.i(TAG, "[$contactName] Session #${sessionIndex} connected after ${attempts * 100}ms")
                            
                            // Create and add session to pool immediately after connection
                            val sessionState = SessionState(
                                webSocket = webSocket,
                                character = character,
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
        
        val iterator = sessionPool.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            val sessionAge = currentTime - session.createdAt
            
            // Remove sessions that are old and not connected
            val isOldEnough = sessionAge > GRACE_PERIOD_MS
            val isActuallyDead = !session.webSocket.isConnected() || session.job.isCancelled
            
            if (isOldEnough && isActuallyDead) {
                Log.d(TAG, "Removing dead session for ${session.character} (age: ${sessionAge/1000}s)")
                session.job.cancel()
                iterator.remove()
            }
        }
        
        Log.d(TAG, "[$contactName] Pool status: ${sessionPool.size}/$poolSize sessions")
    }
    
    // This function is no longer needed with the timer-based approach
    
    private suspend fun sendPreRecordedAudioToSession(webSocket: SesameWebSocket, character: String, sessionNumber: Int) {
        try {
            Log.i(TAG, "Sending pre-recorded audio to background session #$sessionNumber ($character)")
            
            // Use contact-specific audio files
            val audioFileName = when (contactName.lowercase()) {
                "kira" -> "kira.wav"
                "hugo" -> "hugo.wav"
                else -> "openai-fm-coral-eternal-optimist.wav" // Fallback
            }
            
            val audioChunks = audioFileProcessor.loadWavFile(audioFileName)
            if (audioChunks != null && audioChunks.isNotEmpty()) {
                
                // Find the session state to update progress
                val sessionState = sessionPool.find { it.webSocket == webSocket }
                
                // Send audio chunks
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
                    
                    // Original timing - 64ms delay between chunks
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
                delay(2000)
                
                // Session is now "done" - schedule it for replacement
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
        val availableSessions = sessionPool.filter {
            it.isAvailable && !it.isInUse && it.webSocket.isConnected() && it.character == backendCharacter
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
        sessionState.job.cancel()
        sessionState.webSocket.disconnect()
        sessionPool.remove(sessionState)
        Log.d(TAG, "Removed session ${sessionState.character} from pool")
        
        // Create a replacement session to maintain pool size
        pendingCreations.incrementAndGet()
        scope.launch {
            try {
                createSessionWithTimer()
            } finally {
                pendingCreations.decrementAndGet()
            }
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
            it.character == backendCharacter && it.webSocket.isConnected()
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
        val contactSessions = sessionPool.filter { it.character == backendCharacter }
            .sortedBy { it.createdAt } // Sort by creation time to maintain consistent order
        
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