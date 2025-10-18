package com.sesame.voicechat

import android.app.Application
import android.util.Log

class SesameApplication : Application() {
    
    companion object {
        private const val TAG = "SesameApplication"
    }
    
    lateinit var sessionManager: SessionManager
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 SesameApplication starting - initializing session pool")
        
        // Initialize SessionManager singleton as soon as app starts
        sessionManager = SessionManager.getInstance(this)
        
        // Initialize TokenManager first
        val tokenManager = TokenManager(this).apply {
            // Store initial tokens if not already stored
            if (!hasStoredTokens()) {
                Log.i(TAG, "🔐 Loading tokens from secure configuration...")
                
                val tokenPair = TokenConfig.loadTokens(this@SesameApplication)
                if (tokenPair != null) {
                    Log.i(TAG, "✅ Storing tokens from configuration file...")
                    storeTokens(tokenPair.idToken, tokenPair.refreshToken)
                } else {
                    Log.e(TAG, "❌ Failed to load tokens from configuration - app may not function properly")
                }
            }
        }
        
        // Start the session pool - this will immediately begin creating 3 background sessions
        sessionManager.initialize(tokenManager)
        
        Log.i(TAG, "🏊 Session pool initialization started - sessions will continue running for entire app lifecycle!")
    }
    
    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "🛑 App terminating - shutting down session pool")
        if (::sessionManager.isInitialized) {
            sessionManager.shutdown()
        }
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "⚠️ Low memory - but keeping session pool running")
        // Don't shutdown sessions on low memory - they're critical for UX
    }
}