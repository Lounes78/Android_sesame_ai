package com.sesame.voicechat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class TokenManager(private val context: Context, private val contactName: String = "default") {
    
    companion object {
        private const val TAG = "TokenManager"
        private const val FIREBASE_API_KEY = "AIzaSyDtC7Uwb5pGAsdmrH2T4Gqdk5Mga07jYPM"
        private const val REFRESH_TOKEN_URL = "https://securetoken.googleapis.com/v1/token"
    }
    
    // Contact-specific preference names to avoid conflicts
    private val prefsName = "sesame_tokens_${contactName.lowercase()}"
    private val prefIdToken = "id_token_${contactName.lowercase()}"
    private val prefRefreshToken = "refresh_token_${contactName.lowercase()}"
    private val prefTokenExpiry = "token_expiry_${contactName.lowercase()}"
        
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    
    /**
     * Get a valid ID token, automatically refreshing if needed
     */
    suspend fun getValidIdToken(): String? = withContext(Dispatchers.IO) {
        try {
            // Check if current token is still valid
            val currentToken = prefs.getString(prefIdToken, null)
            val tokenExpiry = prefs.getLong(prefTokenExpiry, 0)
            val currentTime = System.currentTimeMillis()
            
            Log.e(TAG, "🚨🚨🚨 TOKEN DEBUG FOR $contactName 🚨🚨🚨")
            Log.e(TAG, "🚨 Current token: ${if (currentToken != null) "Present (${currentToken.take(50)}...)" else "NULL"}")
            Log.e(TAG, "🚨 Token expiry: $tokenExpiry (${java.util.Date(tokenExpiry)})")
            Log.e(TAG, "🚨 Current time: $currentTime (${java.util.Date(currentTime)})")
            Log.e(TAG, "🚨 Time until expiry: ${tokenExpiry - currentTime}ms")
            Log.e(TAG, "🚨 Is valid (with 1min buffer): ${currentTime < tokenExpiry - 60000}")
            
            if (currentToken != null && currentTime < tokenExpiry - 60000) { // 1 min buffer
                Log.e(TAG, "🚨 USING EXISTING VALID TOKEN")
                return@withContext currentToken
            }
            
            // Token expired or missing, try to refresh
            Log.e(TAG, "🚨 TOKEN EXPIRED OR MISSING - ATTEMPTING REFRESH...")
            val refreshToken = prefs.getString(prefRefreshToken, null)
            Log.e(TAG, "🚨 Refresh token: ${if (refreshToken != null) "Present (${refreshToken.take(50)}...)" else "NULL"}")
            
            if (refreshToken != null) {
                Log.e(TAG, "🚨 CALLING REFRESH TOKEN API...")
                val newToken = refreshIdToken(refreshToken)
                if (newToken != null) {
                    Log.e(TAG, "🚨 TOKEN REFRESHED SUCCESSFULLY!")
                    return@withContext newToken
                } else {
                    Log.e(TAG, "🚨 TOKEN REFRESH FAILED!")
                }
            } else {
                Log.e(TAG, "🚨 NO REFRESH TOKEN AVAILABLE!")
            }
            
            Log.e(TAG, "🚨 FINAL RESULT: NO VALID TOKEN AVAILABLE")
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(TAG, "🚨 EXCEPTION in getValidIdToken for $contactName", e)
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
                putString(prefIdToken, idToken)
                putString(prefRefreshToken, refreshToken)
                putLong(prefTokenExpiry, tokenExpiry)
                apply()
            }
            
            Log.i(TAG, "[$contactName] Tokens stored successfully. Expires: ${Date(tokenExpiry)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing tokens", e)
        }
    }
    
    /**
     * Refresh the ID token using the refresh token
     */
    private suspend fun refreshIdToken(refreshToken: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.e(TAG, "🚨 REFRESH TOKEN API CALL STARTING for $contactName")
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
            Log.e(TAG, "🚨 Request URL: $url")
            Log.e(TAG, "🚨 Request body: $requestBody")
            
            // Send request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }
            
            // Read response
            val responseCode = connection.responseCode
            Log.e(TAG, "🚨 Response code: $responseCode")
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.e(TAG, "🚨 Success response: ${response.take(200)}...")
                val jsonResponse = JSONObject(response)
                
                val newIdToken = jsonResponse.getString("id_token")
                val newRefreshToken = jsonResponse.getString("refresh_token")
                
                Log.e(TAG, "🚨 New ID token received: ${newIdToken.take(50)}...")
                Log.e(TAG, "🚨 New refresh token received: ${newRefreshToken.take(50)}...")
                
                // Store the new tokens
                storeTokens(newIdToken, newRefreshToken)
                Log.e(TAG, "🚨 NEW TOKENS STORED SUCCESSFULLY")
                
                return@withContext newIdToken
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "🚨 TOKEN REFRESH FAILED: $responseCode")
                Log.e(TAG, "🚨 Error response: $errorResponse")
                return@withContext null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "🚨 NETWORK ERROR during token refresh for $contactName", e)
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
        return prefs.getString(prefIdToken, null) != null &&
               prefs.getString(prefRefreshToken, null) != null
    }
    
    /**
     * Clear all stored tokens (for logout)
     */
    fun clearTokens() {
        prefs.edit().clear().apply()
        Log.i(TAG, "[$contactName] All tokens cleared")
    }
    
    /**
     * Get current token info for debugging
     */
    fun getTokenInfo(): String {
        val idToken = prefs.getString(prefIdToken, null)
        val refreshToken = prefs.getString(prefRefreshToken, null)
        val expiry = prefs.getLong(prefTokenExpiry, 0)
        
        return buildString {
            appendLine("[$contactName] Token Status:")
            appendLine("• ID Token: ${if (idToken != null) "Present" else "Missing"}")
            appendLine("• Refresh Token: ${if (refreshToken != null) "Present" else "Missing"}")
            appendLine("• Expires: ${if (expiry > 0) Date(expiry) else "Unknown"}")
            appendLine("• Valid: ${System.currentTimeMillis() < expiry}")
        }
    }
}