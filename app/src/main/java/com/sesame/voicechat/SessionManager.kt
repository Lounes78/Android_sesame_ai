package com.sesame.voicechat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SessionManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SessionManager"
        private const val POOL_SIZE = 10
        private var INSTANCE: SessionManager? = null
        
        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    data class SessionState(
        val webSocket: SesameWebSocket,
        val character: String,
        val createdAt: Long,
        var isPromptComplete: Boolean = false,
        var promptProgress: Float = 0f,
        var isAvailable: Boolean = true,
        var isInUse: Boolean = false,
        val job: Job
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
    private val sessionCounter = AtomicInteger(0) // Simple sequential counter for session numbers
    private lateinit var tokenManager: TokenManager
    private lateinit var audioFileProcessor: AudioFileProcessor
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    fun initialize(tokenManager: TokenManager) {
        this.tokenManager = tokenManager
        this.audioFileProcessor = AudioFileProcessor(context)
        
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "🏊 Initializing session pool with $POOL_SIZE sessions (6-second intervals)")
            startPoolMaintenance()
        }
    }
    
    private fun startPoolMaintenance() {
        scope.launch {
            while (isRunning.get()) {
                try {
                    maintainSessionPool()
                    delay(5000) // Check pool every 5 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Pool maintenance error", e)
                    delay(10000) // Wait longer on error
                }
            }
        }
    }
    
    private suspend fun maintainSessionPool() {
        val currentSessions = sessionPool.size
        val availableSessions = sessionPool.count { it.isAvailable && !it.isInUse }
        val connectedSessions = sessionPool.count { it.webSocket.isConnected() }
        val pending = pendingCreations.get()
        
        Log.d(TAG, "🏊 Pool status: $currentSessions total, $availableSessions available, $connectedSessions connected, $pending pending")
        
        // Only remove truly dead sessions, but give new sessions time to connect
        val currentTime = System.currentTimeMillis()
        val GRACE_PERIOD_MS = 15000L // 15 seconds grace period for new sessions to connect
        
        val iterator = sessionPool.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            val sessionAge = currentTime - session.createdAt
            
            // Only consider removal if session is old enough AND (not connected OR job cancelled)
            val isOldEnough = sessionAge > GRACE_PERIOD_MS
            val isActuallyDead = !session.webSocket.isConnected() || session.job.isCancelled
            
            if (isOldEnough && isActuallyDead) {
                Log.d(TAG, "🗑️ Removing dead session for ${session.character} (age: ${sessionAge/1000}s)")
                session.job.cancel()
                iterator.remove()
            } else if (!isOldEnough && !session.webSocket.isConnected()) {
                Log.d(TAG, "⏳ Keeping new session for ${session.character} (age: ${sessionAge/1000}s, still connecting)")
            }
        }
        
        // Add new sessions ONLY to maintain the exact pool size of 10 (including pending)
        val totalExpected = sessionPool.size + pendingCreations.get()
        val sessionsToCreate = POOL_SIZE - totalExpected
        if (sessionsToCreate > 0) {
            Log.i(TAG, "🆕 Creating $sessionsToCreate new sessions to maintain pool size (total: $totalExpected/$POOL_SIZE)")
            // Track pending creations to prevent overshooting
            pendingCreations.addAndGet(sessionsToCreate)
            
            // Create replacement sessions with staggered delays to avoid server overload
            repeat(sessionsToCreate) { index ->
                scope.launch {
                    try {
                        // Add 2-second delay between each replacement session creation
                        delay(index * 2000L)
                        createNewSession()
                    } finally {
                        // Decrement pending counter whether success or failure
                        pendingCreations.decrementAndGet()
                    }
                }
            }
        }
        
        // Mark initial pool creation as complete when we reach full size
        if (sessionPool.size >= POOL_SIZE && isInitialPoolCreation.get()) {
            isInitialPoolCreation.set(false)
            Log.i(TAG, "🏁 Initial pool creation complete (${POOL_SIZE} sessions) - future sessions will be replacements")
        }
    }
    
    private suspend fun createNewSession() {
        try {
            val validToken = tokenManager.getValidIdToken()
            if (validToken == null) {
                Log.e(TAG, "❌ Cannot create session: no valid token")
                return
            }
            
            // Only create sessions for Maya for now
            val character = "Maya"
            
            // Determine if this is initial pool creation or a replacement
            val isReplacement = !isInitialPoolCreation.get()
            val promptLengthSeconds = 60L // Approximate prompt duration in seconds
            val intervalSeconds = promptLengthSeconds / POOL_SIZE // 6 seconds
            
            val (sessionIndex, delaySeconds) = if (isReplacement) {
                // For replacement sessions, calculate optimal timing to maintain rotation
                val replacementNumber = sessionCounter.incrementAndGet()
                val currentTime = System.currentTimeMillis()
                val existingSessions = sessionPool.toList()
                
                // Calculate when the next slot should start based on optimal rotation
                val nextOptimalStartTime = if (existingSessions.isNotEmpty()) {
                    // Find the most recent session start time and add interval
                    val lastStartTime = existingSessions.maxOfOrNull { it.createdAt } ?: currentTime
                    val timeSinceLastStart = currentTime - lastStartTime
                    val nextSlotDelay = Math.max(0L, intervalSeconds * 1000L - timeSinceLastStart)
                    nextSlotDelay / 1000L // Convert to seconds
                } else {
                    0L // No existing sessions, start immediately
                }
                
                Log.i(TAG, "🔄 Creating replacement session #$replacementNumber - delayed ${nextOptimalStartTime}s to maintain rotation")
                Pair(replacementNumber, nextOptimalStartTime)
            } else {
                // For initial pool creation, use staggered timing based on current count
                val sessionNumber = sessionCounter.incrementAndGet()
                Log.i(TAG, "🌱 Creating initial session #${sessionNumber} with staggered timing")
                Pair(sessionNumber, (sessionNumber - 1) * intervalSeconds)
            }
            
            Log.i(TAG, "🎬 Creating new session #${sessionIndex} for $character (will start prompt in ${delaySeconds}s) [${promptLengthSeconds}s/${POOL_SIZE} = ${intervalSeconds}s intervals]")
            
            val webSocket = SesameWebSocket(validToken, character).apply {
                onConnectCallback = {
                    Log.d(TAG, "✅ Background session #${sessionIndex} connected for $character")
                }
                onDisconnectCallback = {
                    Log.d(TAG, "❌ Background session #${sessionIndex} disconnected for $character")
                }
                onErrorCallback = { error ->
                    Log.e(TAG, "Background session #${sessionIndex} error for $character: $error")
                }
            }
            
            if (webSocket.connect()) {
                val sessionJob = scope.launch {
                    try {
                        // Wait for connection
                        var attempts = 0
                        while (!webSocket.isConnected() && attempts < 30) {
                            delay(100)
                            attempts++
                        }
                        
                        if (webSocket.isConnected()) {
                            // Wait for the staggered delay before starting prompt
                            if (delaySeconds > 0) {
                                Log.i(TAG, "⏳ Session #${sessionIndex} waiting ${delaySeconds}s before starting prompt...")
                                delay(delaySeconds * 1000)
                            }
                            
                            // Send pre-recorded audio in background
                            sendPreRecordedAudioToSession(webSocket, character, sessionIndex)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Session job error for $character #${sessionIndex + 1}", e)
                    }
                }
                
                val sessionState = SessionState(
                    webSocket = webSocket,
                    character = character,
                    createdAt = System.currentTimeMillis(),
                    job = sessionJob
                )
                
                sessionPool.offer(sessionState)
                Log.i(TAG, "➕ Added session #${sessionIndex} for $character to pool (${sessionPool.size}/$POOL_SIZE) [6s intervals]")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating session", e)
        }
    }
    
    private suspend fun sendPreRecordedAudioToSession(webSocket: SesameWebSocket, character: String, sessionNumber: Int) {
        try {
            Log.i(TAG, "🎵 Sending pre-recorded audio to background session #$sessionNumber ($character)")
            
            val audioChunks = audioFileProcessor.loadWavFile("openai-fm-coral-eternal-optimist.wav")
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
                        Log.e(TAG, "❌ Failed to send audio chunk $i to $character")
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
                    Log.i(TAG, "✅ Session #$sessionNumber ($character) prompt complete - session is now done")
                }
                
                // Wait for AI response processing
                delay(2000)
                
                // Session is now "done" - schedule it for replacement
                sessionState?.let { state ->
                    scope.launch {
                        delay(5000) // Keep the completed session available for 5 seconds for immediate use
                        
                        // Check if session is still not in use, then replace it
                        if (!state.isInUse) {
                            Log.i(TAG, "🔄 Session #$sessionNumber ($character) done and unused - replacing with new session")
                            
                            // Remove the completed session
                            state.job.cancel()
                            state.webSocket.disconnect()
                            sessionPool.remove(state)
                            
                            // Create a new session to maintain pool size
                            pendingCreations.incrementAndGet()
                            try {
                                createNewSession()
                            } finally {
                                pendingCreations.decrementAndGet()
                            }
                        } else {
                            Log.i(TAG, "📞 Session #$sessionNumber ($character) is in use - keeping it")
                        }
                    }
                }
                
            } else {
                Log.e(TAG, "❌ Failed to load audio file for background session #$sessionNumber ($character)")
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
        val availableSessions = sessionPool.filter {
            it.isAvailable && !it.isInUse && it.webSocket.isConnected() && it.character == "Maya"
        }
        
        if (availableSessions.isEmpty()) {
            Log.w(TAG, "⚠️ No available Maya sessions in pool")
            return null
        }
        
        // Find session with highest progress (closest to completion)
        val bestSession = availableSessions.maxByOrNull { session ->
            if (session.isPromptComplete) 100f else session.promptProgress
        }
        
        bestSession?.let { session ->
            session.isInUse = true
            val status = if (session.isPromptComplete) "complete" else "${(session.promptProgress * 100).toInt()}%"
            Log.i(TAG, "🎯 Using Maya session (prompt $status)")
        }
        
        return bestSession
    }
    
    /**
     * Return a session to the pool after use - session can be reused
     */
    fun returnSession(sessionState: SessionState) {
        sessionState.isInUse = false
        Log.d(TAG, "🔄 Returned session ${sessionState.character} to pool - can be reused")
        // No replacement needed - session continues to exist and can be reused
    }
    
    /**
     * Remove a session from the pool only when it's truly done (WebSocket failed, etc.)
     */
    fun removeSession(sessionState: SessionState) {
        sessionState.job.cancel()
        sessionState.webSocket.disconnect()
        sessionPool.remove(sessionState)
        Log.d(TAG, "🗑️ Removed session ${sessionState.character} from pool")
        
        // Create a replacement session to maintain pool size
        pendingCreations.incrementAndGet()
        scope.launch {
            try {
                createNewSession()
            } finally {
                pendingCreations.decrementAndGet()
            }
        }
    }
    
    fun getPoolStatus(): String {
        val total = sessionPool.size
        val available = sessionPool.count { it.isAvailable && !it.isInUse }
        val complete = sessionPool.count { it.isPromptComplete }
        return "Pool: $total total, $available available, $complete ready"
    }
    
    /**
     * Get session progress info without claiming the session
     * Returns progress of the most advanced session
     */
    fun getSessionProgress(): Pair<Float, Boolean>? {
        val mayaSessions = sessionPool.filter {
            it.character == "Maya" && it.webSocket.isConnected()
        }
        
        if (mayaSessions.isEmpty()) {
            return null
        }
        
        // Find the session with highest progress, prioritizing completed ones
        val bestSession = mayaSessions.maxWithOrNull(compareBy<SessionState> {
            if (it.isPromptComplete) 1000f else it.promptProgress
        }.thenBy { it.promptProgress })
        
        return bestSession?.let {
            Log.d(TAG, "🔍 Session progress check: ${it.promptProgress * 100}% complete=${it.isPromptComplete}")
            Pair(it.promptProgress, it.isPromptComplete)
        }
    }
    
    /**
     * Get progress info for all sessions (for UI display)
     */
    fun getAllSessionsProgress(): List<SessionInfo> {
        val mayaSessions = sessionPool.filter { it.character == "Maya" }
            .sortedBy { it.createdAt } // Sort by creation time to maintain consistent order
        
        return mayaSessions.map { session ->
            SessionInfo(
                progress = session.promptProgress,
                isComplete = session.isPromptComplete,
                isConnected = session.webSocket.isConnected()
            )
        }
    }
    
    fun shutdown() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "🛑 Shutting down session pool")
            
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