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
        get() = getSessionManagerForContact("Kira-EN") // Default to Kira English for backward compatibility
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SesameApplication starting")
        
        configPrefs = ConfigurationPreferences(this)
        
        // Check if configuration exists - if not, defer initialization
        val config = configPrefs.getConfiguration()
        if (config == null) {
            Log.i(TAG, "No configuration found - session pools will initialize after configuration")
            return
        }
        
        Log.i(TAG, "Found configuration: $config")
        initializeWithConfiguration(config)
    }
    
    fun initializeWithConfiguration(config: SessionConfiguration) {
        Log.i(TAG, "Initializing session pools with configuration: $config")
        
        // Load all available contact tokens
        val allTokens = TokenConfig.loadAllContactTokens(this)
        if (allTokens == null) {
            Log.e(TAG, "Failed to load any tokens from configuration")
            return
        }
        
        // Initialize session pools for character-language combinations to support language-specific prompts
        val contactKeys = mutableListOf<String>()
        
        // Create only English character combinations (French temporarily disabled)
        if (config.kiraEnabled && allTokens.kira != null) {
            contactKeys.add("Kira-EN")
        }
        if (config.hugoEnabled && allTokens.hugo != null) {
            contactKeys.add("Hugo-EN")
        }
        
        if (contactKeys.isEmpty()) {
            Log.w(TAG, "No enabled contacts with valid tokens found")
            return
        }
        
        Log.i(TAG, "Initializing session pools for: ${contactKeys.joinToString(", ")} with pool size ${config.poolSize}")
        
        contactKeys.forEach { contactKey ->
            initializeContactSessionPool(contactKey, config.poolSize)
        }
        
        Log.i(TAG, "All session pools initialized - sessions will continue running for entire app lifecycle!")
    }
    
    private fun initializeContactSessionPool(contactKey: String, poolSize: Int) {
        Log.i(TAG, "Initializing session pool for $contactKey with pool size $poolSize")
        
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
        
        // Initialize the session pool
        sessionManager.initialize(tokenManager)
        
        Log.i(TAG, "[$contactKey] Session pool initialization started with $poolSize sessions")
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
        Log.i(TAG, "Force restarting all session pools to clear French sessions")
        
        // Get current configuration
        val config = configPrefs.getConfiguration()
        if (config != null) {
            // Restart with current configuration to clear any existing French sessions
            restartWithNewConfiguration(config)
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