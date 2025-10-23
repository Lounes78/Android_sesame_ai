package com.sesame.voicechat

import android.content.Context
import android.content.SharedPreferences

data class SessionConfiguration(
    val poolSize: Int = 8,
    val kiraEnabled: Boolean = true,
    val hugoEnabled: Boolean = true
)

class ConfigurationPreferences(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "session_config_prefs"
        private const val KEY_POOL_SIZE = "pool_size"
        private const val KEY_KIRA_ENABLED = "kira_enabled"
        private const val KEY_HUGO_ENABLED = "hugo_enabled"
        private const val KEY_CONFIG_SET = "config_set"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun saveConfiguration(config: SessionConfiguration) {
        prefs.edit().apply {
            putInt(KEY_POOL_SIZE, config.poolSize)
            putBoolean(KEY_KIRA_ENABLED, config.kiraEnabled)
            putBoolean(KEY_HUGO_ENABLED, config.hugoEnabled)
            putBoolean(KEY_CONFIG_SET, true)
            apply()
        }
    }
    
    fun getConfiguration(): SessionConfiguration? {
        // Return null if no configuration has been set yet
        if (!prefs.getBoolean(KEY_CONFIG_SET, false)) {
            return null
        }
        
        return SessionConfiguration(
            poolSize = prefs.getInt(KEY_POOL_SIZE, 8),
            kiraEnabled = prefs.getBoolean(KEY_KIRA_ENABLED, true),
            hugoEnabled = prefs.getBoolean(KEY_HUGO_ENABLED, true)
        )
    }
    
    fun hasConfiguration(): Boolean {
        return prefs.getBoolean(KEY_CONFIG_SET, false)
    }
    
    fun clearConfiguration() {
        prefs.edit().clear().apply()
    }
}