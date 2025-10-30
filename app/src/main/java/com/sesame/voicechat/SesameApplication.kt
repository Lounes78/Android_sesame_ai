package com.sesame.voicechat

import android.app.Application
import android.util.Log

class SesameApplication : Application() {
    
    companion object {
        private const val TAG = "SesameApplication"
    }
    
    private val sessionManagers = mutableMapOf<String, SessionManager>()
    private val tokenManagers = mutableMapOf<String, TokenManager>()
    private lateinit var configPrefs: ConfigurationPreferences
    
    // Legacy support for single sessionManager
    val sessionManager: SessionManager
        get() = getSessionManagerForContact("Kira-FR") // Default to Kira French
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SesameApplication starting")
        
        configPrefs = ConfigurationPreferences(this)
        
        // Never auto-start session pools - user must explicitly click "START SESSION POOLS"
        val config = configPrefs.getConfiguration()
        if (config == null) {
            Log.i(TAG, "No configuration found - waiting for user configuration")
        } else {
            Log.i(TAG, "Found existing configuration: $config")
            Log.i(TAG, "Session pools will NOT start automatically - user must click 'START SESSION POOLS'")
        }
    }
    
    fun initializeWithConfiguration(config: SessionConfiguration) {
        Log.i(TAG, "Initializing session pools with configuration: $config")
        
        // Load all available contact tokens
        val allTokens = TokenConfig.loadAllContactTokens(this)
        if (allTokens == null) {
            Log.e(TAG, "Failed to load any tokens from configuration")
            return
        }
        
        // Create session managers but DON'T start them automatically
        val contactKeys = mutableListOf<String>()
        
        // Create French-only sessions
        if (config.kiraEnabled && allTokens.kira != null) {
            contactKeys.add("Kira-FR")
        }
        if (config.hugoEnabled && allTokens.hugo != null) {
            contactKeys.add("Hugo-FR")
        }
        
        if (contactKeys.isEmpty()) {
            Log.w(TAG, "No enabled contacts with valid tokens found")
            return
        }
        
        Log.i(TAG, "Preparing session managers for: ${contactKeys.joinToString(", ")} with pool size ${config.poolSize}")
        Log.i(TAG, "Session pools will NOT start automatically - user must click 'START SESSION POOLS'")
        
        contactKeys.forEach { contactKey ->
            prepareContactSessionManager(contactKey, config.poolSize)
        }
        
        Log.i(TAG, "All session managers prepared - waiting for user to start session pools!")
    }
    
    private fun prepareContactSessionManager(contactKey: String, poolSize: Int) {
        Log.i(TAG, "Preparing session manager for $contactKey with pool size $poolSize (NOT starting yet)")
        
        // Extract character from contact key (e.g., "Kira-EN" -> "Kira")
        val characterName = contactKey.split("-").firstOrNull()
        if (characterName == null) {
            Log.e(TAG, "Invalid contact key format: $contactKey")
            return
        }
        
        // Get contact-specific tokens using the character name
        val contactTokens = TokenConfig.getTokensForContact(this, characterName)
        if (contactTokens == null) {
            Log.e(TAG, "No tokens found for character $characterName (contactKey: $contactKey)")
            return
        }
        
        // Create contact-specific TokenManager using the character name for tokens
        val tokenManager = TokenManager(this, characterName).apply {
            // FORCE RELOAD: Clear any existing tokens to use updated tokens from configuration
            clearStoredTokensToForceReload()
            
            Log.i(TAG, "[$contactKey] Loading fresh tokens from configuration...")
            storeTokens(contactTokens.idToken, contactTokens.refreshToken)
        }
        tokenManagers[contactKey] = tokenManager
        
        // Create contact-specific SessionManager with configured pool size using the full contact key
        val sessionManager = SessionManager.getInstance(this, contactKey, poolSize)
        sessionManagers[contactKey] = sessionManager
        
        // DON'T initialize the session pool yet - wait for user to click "START SESSION POOLS"
        
        Log.i(TAG, "[$contactKey] Session manager prepared - ready to start when user clicks button")
    }
    
    private fun initializeContactSessionPool(contactKey: String, poolSize: Int) {
        Log.i(TAG, "Starting session pool for $contactKey with pool size $poolSize")
        
        val tokenManager = tokenManagers[contactKey]
        val sessionManager = sessionManagers[contactKey]
        
        if (tokenManager == null || sessionManager == null) {
            Log.e(TAG, "Session manager or token manager not found for $contactKey")
            return
        }
        
        // NOW initialize the session pool
        sessionManager.initialize(tokenManager)
        
        Log.i(TAG, "[$contactKey] Session pool started with $poolSize sessions")
    }
    
    fun startAllSessionPools() {
        Log.i(TAG, "Starting all prepared session pools...")
        
        sessionManagers.keys.forEach { contactKey ->
            initializeContactSessionPool(contactKey, 2) // Use poolSize from config
        }
        
        Log.i(TAG, "All session pools started!")
    }
    
    fun getSessionManagerForContact(contactName: String): SessionManager {
        return sessionManagers[contactName]
            ?: throw IllegalArgumentException("No SessionManager found for contact: $contactName. Available: ${sessionManagers.keys}")
    }
    
    fun getAvailableContacts(): List<String> {
        return sessionManagers.keys.toList()
    }
    
    fun hasConfiguration(): Boolean {
        return configPrefs.hasConfiguration()
    }
    
    fun forceRestartAllSessionPools() {
        Log.i(TAG, "Force restarting all session pools to clear mixed language sessions")
        
        // Get current configuration
        val config = configPrefs.getConfiguration()
        if (config != null) {
            // Restart with current configuration to clear any existing mixed sessions
            restartWithNewConfiguration(config)
        }
    }
    
    fun forceRestartToFixLanguageIssue() {
        Log.i(TAG, "Force restarting to fix language session mixing issue")
        
        // Shutdown all existing session pools to clear old sessions
        sessionManagers.values.forEach { it.shutdown() }
        sessionManagers.clear()
        tokenManagers.clear()
        
        // Clear static instances in SessionManager to force fresh creation
        SessionManager.clearAllInstances()
        
        // Restart with current configuration
        val config = configPrefs.getConfiguration()
        if (config != null) {
            initializeWithConfiguration(config)
            // Auto-start the pools
            startAllSessionPools()
        }
    }
    
    fun restartWithNewConfiguration(config: SessionConfiguration) {
        Log.i(TAG, "Restarting with new configuration: $config")
        
        // Shutdown existing session pools
        sessionManagers.values.forEach { it.shutdown() }
        sessionManagers.clear()
        tokenManagers.clear()
        
        // Initialize with new configuration
        initializeWithConfiguration(config)
    }
    
    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "App terminating - shutting down all session pools")
        sessionManagers.values.forEach { sessionManager ->
            sessionManager.shutdown()
        }
        sessionManagers.clear()
        tokenManagers.clear()
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory - but keeping all session pools running")
        // Don't shutdown sessions on low memory - they're critical for UX
    }
}