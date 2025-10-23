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
        get() = getSessionManagerForContact("Kira") // Default to Kira for backward compatibility
    
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
        
        // Initialize session pools only for enabled contacts
        val contacts = mutableListOf<String>()
        if (config.kiraEnabled && allTokens.kira != null) {
            contacts.add("Kira")
        }
        if (config.hugoEnabled && allTokens.hugo != null) {
            contacts.add("Hugo")
        }
        
        if (contacts.isEmpty()) {
            Log.w(TAG, "No enabled contacts with valid tokens found")
            return
        }
        
        Log.i(TAG, "Initializing session pools for: ${contacts.joinToString(", ")} with pool size ${config.poolSize}")
        
        contacts.forEach { contactName ->
            initializeContactSessionPool(contactName, config.poolSize)
        }
        
        Log.i(TAG, "All session pools initialized - sessions will continue running for entire app lifecycle!")
    }
    
    private fun initializeContactSessionPool(contactName: String, poolSize: Int) {
        Log.i(TAG, "Initializing session pool for $contactName with pool size $poolSize")
        
        // Get contact-specific tokens
        val contactTokens = TokenConfig.getTokensForContact(this, contactName)
        if (contactTokens == null) {
            Log.e(TAG, "No tokens found for $contactName")
            return
        }
        
        // Create contact-specific TokenManager
        val tokenManager = TokenManager(this, contactName).apply {
            // Store tokens for this contact if not already stored
            if (!hasStoredTokens()) {
                Log.i(TAG, "[$contactName] Storing tokens from configuration...")
                storeTokens(contactTokens.idToken, contactTokens.refreshToken)
            }
        }
        tokenManagers[contactName] = tokenManager
        
        // Create contact-specific SessionManager with configured pool size
        val sessionManager = SessionManager.getInstance(this, contactName, poolSize)
        sessionManagers[contactName] = sessionManager
        
        // Initialize the session pool
        sessionManager.initialize(tokenManager)
        
        Log.i(TAG, "[$contactName] Session pool initialization started with $poolSize sessions")
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