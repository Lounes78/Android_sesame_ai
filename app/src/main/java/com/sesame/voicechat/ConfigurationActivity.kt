package com.sesame.voicechat

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ConfigurationActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ConfigurationActivity"
    }
    
    private lateinit var poolSizeSeekBar: SeekBar
    private lateinit var poolSizeLabel: TextView
    private lateinit var startButton: Button
    private lateinit var configPrefs: ConfigurationPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configuration)
        
        configPrefs = ConfigurationPreferences(this)
        
        initViews()
        setupListeners()
        loadExistingConfiguration()
    }
    
    private fun initViews() {
        poolSizeSeekBar = findViewById(R.id.pool_size_seekbar)
        poolSizeLabel = findViewById(R.id.pool_size_label)
        startButton = findViewById(R.id.start_button)
        
        // Set initial values
        poolSizeSeekBar.min = 2
        poolSizeSeekBar.max = 12
        poolSizeSeekBar.progress = 8 // Default to 8
        
        updatePoolSizeLabel()
    }
    
    private fun loadExistingConfiguration() {
        val existingConfig = configPrefs.getConfiguration()
        if (existingConfig != null) {
            Log.i(TAG, "Loading existing configuration: $existingConfig")
            poolSizeSeekBar.progress = existingConfig.poolSize
            updatePoolSizeLabel()
        }
    }
    
    private fun setupListeners() {
        poolSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updatePoolSizeLabel()
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        startButton.setOnClickListener {
            if (validateSelection()) {
                startSessionPools()
            }
        }
    }
    
    private fun updatePoolSizeLabel() {
        val size = poolSizeSeekBar.progress
        val activeContacts = listOf("Kira", "Hugo") // Both always enabled
        
        val totalSessions = size * activeContacts.size
        poolSizeLabel.text = "Pool Size: $size per contact (Total: $totalSessions sessions)"
    }
    
    private fun validateSelection(): Boolean {
        // Both contacts are always enabled now
        return true
    }
    
    private fun startSessionPools() {
        val poolSize = poolSizeSeekBar.progress
        val kiraEnabled = true // Always enabled
        val hugoEnabled = true // Always enabled
        
        // Validate pool size
        if (poolSize < 2) {
            Toast.makeText(this, "Pool size must be at least 2", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Save configuration
        val config = SessionConfiguration(
            poolSize = poolSize,
            kiraEnabled = kiraEnabled,
            hugoEnabled = hugoEnabled
        )
        
        Log.i(TAG, "Saving configuration: $config")
        configPrefs.saveConfiguration(config)
        
        // Get SesameApplication and restart with new configuration
        val application = getApplication() as SesameApplication
        
        // Force restart to fix any language session mixing issues
        application.forceRestartToFixLanguageIssue()
        
        Toast.makeText(this, "Configuration saved! Session pools restarted with language isolation...", Toast.LENGTH_SHORT).show()
        
        // Navigate to ContactSelectionActivity
        val intent = Intent(this, ContactSelectionActivity::class.java)
        startActivity(intent)
        finish()
    }
}