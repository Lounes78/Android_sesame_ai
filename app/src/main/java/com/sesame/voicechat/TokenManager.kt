package com.sesame.voicechat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class TokenManager(private val context: Context) {
    
    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "sesame_tokens"
        private const val PREF_ID_TOKEN = "id_token"
        private const val PREF_REFRESH_TOKEN = "refresh_token"
        private const val PREF_TOKEN_EXPIRY = "token_expiry"
        
        // Firebase API configuration
        private const val FIREBASE_API_KEY = "AIzaSyDtC7Uwb5pGAsdmrH2T4Gqdk5Mga07jYPM"
        private const val REFRESH_TOKEN_URL = "https://securetoken.googleapis.com/v1/token"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Get a valid ID token, automatically refreshing if needed
     */
    suspend fun getValidIdToken(): String? = withContext(Dispatchers.IO) {
        try {
            // Check if current token is still valid
            val currentToken = prefs.getString(PREF_ID_TOKEN, null)
            val tokenExpiry = prefs.getLong(PREF_TOKEN_EXPIRY, 0)
            
            if (currentToken != null && System.currentTimeMillis() < tokenExpiry - 60000) { // 1 min buffer
                Log.d(TAG, "✅ Using existing valid token")
                return@withContext currentToken
            }
            
            // Token expired or missing, try to refresh
            Log.i(TAG, "🔄 Token expired or missing, attempting refresh...")
            val refreshToken = prefs.getString(PREF_REFRESH_TOKEN, null)
            
            if (refreshToken != null) {
                val newToken = refreshIdToken(refreshToken)
                if (newToken != null) {
                    Log.i(TAG, "✅ Token refreshed successfully")
                    return@withContext newToken
                }
            }
            
            Log.e(TAG, "❌ Failed to get valid token - user needs to re-authenticate")
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting valid token", e)
            return@withContext null
        }
    }
    
    /**
     * Store initial tokens (typically from manual authentication)
     */
    fun storeTokens(idToken: String, refreshToken: String) {
        try {
            // Parse JWT to get expiration time
            val tokenExpiry = parseJwtExpiry(idToken)
            
            prefs.edit().apply {
                putString(PREF_ID_TOKEN, idToken)
                putString(PREF_REFRESH_TOKEN, refreshToken)
                putLong(PREF_TOKEN_EXPIRY, tokenExpiry)
                apply()
            }
            
            Log.i(TAG, "🔐 Tokens stored successfully. Expires: ${Date(tokenExpiry)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing tokens", e)
        }
    }
    
    /**
     * Refresh the ID token using the refresh token
     */
    private suspend fun refreshIdToken(refreshToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$REFRESH_TOKEN_URL?key=$FIREBASE_API_KEY")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }
            
            // Prepare request body
            val requestBody = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
            }
            
            // Send request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }
            
            // Read response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                
                val newIdToken = jsonResponse.getString("id_token")
                val newRefreshToken = jsonResponse.getString("refresh_token")
                
                // Store the new tokens
                storeTokens(newIdToken, newRefreshToken)
                
                return@withContext newIdToken
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "❌ Token refresh failed: $responseCode - $errorResponse")
                return@withContext null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Network error during token refresh", e)
            return@withContext null
        }
    }
    
    /**
     * Parse JWT token to extract expiration time
     */
    private fun parseJwtExpiry(token: String): Long {
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                // Decode JWT payload (Base64)
                val payload = String(Base64.getDecoder().decode(parts[1]))
                val jsonPayload = JSONObject(payload)
                
                // Get 'exp' field (Unix timestamp)
                val exp = jsonPayload.getLong("exp")
                return exp * 1000 // Convert to milliseconds
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JWT expiry", e)
        }
        
        // Default to 1 hour from now if parsing fails
        return System.currentTimeMillis() + 3600000
    }
    
    /**
     * Check if we have stored tokens
     */
    fun hasStoredTokens(): Boolean {
        return prefs.getString(PREF_ID_TOKEN, null) != null && 
               prefs.getString(PREF_REFRESH_TOKEN, null) != null
    }
    
    /**
     * Clear all stored tokens (for logout)
     */
    fun clearTokens() {
        prefs.edit().clear().apply()
        Log.i(TAG, "🗑️ All tokens cleared")
    }
    
    /**
     * Get current token info for debugging
     */
    fun getTokenInfo(): String {
        val idToken = prefs.getString(PREF_ID_TOKEN, null)
        val refreshToken = prefs.getString(PREF_REFRESH_TOKEN, null)
        val expiry = prefs.getLong(PREF_TOKEN_EXPIRY, 0)
        
        return buildString {
            appendLine("Token Status:")
            appendLine("• ID Token: ${if (idToken != null) "Present" else "Missing"}")
            appendLine("• Refresh Token: ${if (refreshToken != null) "Present" else "Missing"}")
            appendLine("• Expires: ${if (expiry > 0) Date(expiry) else "Unknown"}")
            appendLine("• Valid: ${System.currentTimeMillis() < expiry}")
        }
    }
}