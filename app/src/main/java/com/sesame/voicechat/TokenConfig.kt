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
    
    data class ContactTokens(
        val kira: TokenPair?,
        val hugo: TokenPair?
    )
    
    fun loadTokens(context: Context): TokenPair? {
        // Legacy method for backward compatibility - loads Kira's tokens
        return loadAllContactTokens(context)?.kira
    }
    
    fun loadAllContactTokens(context: Context): ContactTokens? {
        return try {
            val properties = Properties()
            context.assets.open(TOKENS_FILE).use { inputStream ->
                properties.load(inputStream)
            }
            
            // Load Kira's tokens
            val kiraIdToken = properties.getProperty("id_token_kira")
            val kiraRefreshToken = properties.getProperty("refresh_token_kira")
            val kiraTokens = if (!kiraIdToken.isNullOrBlank() && !kiraRefreshToken.isNullOrBlank()) {
                TokenPair(kiraIdToken, kiraRefreshToken)
            } else null
            
            // Load Hugo's tokens
            val hugoIdToken = properties.getProperty("id_token_hugo")
            val hugoRefreshToken = properties.getProperty("refresh_token_hugo")
            val hugoTokens = if (!hugoIdToken.isNullOrBlank() && !hugoRefreshToken.isNullOrBlank()) {
                TokenPair(hugoIdToken, hugoRefreshToken)
            } else null
            
            if (kiraTokens == null && hugoTokens == null) {
                Log.e(TAG, "❌ No valid tokens found in $TOKENS_FILE")
                return null
            }
            
            Log.i(TAG, "✅ Successfully loaded tokens - Kira: ${kiraTokens != null}, Hugo: ${hugoTokens != null}")
            ContactTokens(kiraTokens, hugoTokens)
            
        } catch (e: IOException) {
            Log.e(TAG, "❌ Failed to load tokens from $TOKENS_FILE", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error reading tokens", e)
            null
        }
    }
    
    fun getTokensForContact(context: Context, contactName: String): TokenPair? {
        val allTokens = loadAllContactTokens(context) ?: return null
        return when (contactName.lowercase()) {
            "kira" -> allTokens.kira
            "hugo" -> allTokens.hugo
            else -> {
                Log.w(TAG, "Unknown contact: $contactName")
                null
            }
        }
    }
}