package com.sesame.voicechat

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.Properties

object TokenConfig {
    private const val TAG = "TokenConfig"
    private const val TOKENS_FILE = "tokens.properties"
    
    data class TokenPair(
        val idToken: String,
        val refreshToken: String
    )
    
    fun loadTokens(context: Context): TokenPair? {
        return try {
            val properties = Properties()
            context.assets.open(TOKENS_FILE).use { inputStream ->
                properties.load(inputStream)
            }
            
            val idToken = properties.getProperty("id_token")
            val refreshToken = properties.getProperty("refresh_token")
            
            if (idToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
                Log.e(TAG, "❌ Missing tokens in $TOKENS_FILE")
                return null
            }
            
            Log.i(TAG, "✅ Successfully loaded tokens from $TOKENS_FILE")
            TokenPair(idToken, refreshToken)
            
        } catch (e: IOException) {
            Log.e(TAG, "❌ Failed to load tokens from $TOKENS_FILE", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error reading tokens", e)
            null
        }
    }
}