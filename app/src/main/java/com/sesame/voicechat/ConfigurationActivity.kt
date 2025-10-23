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
    private lateinit var kiraCheckbox: CheckBox
    private lateinit var hugoCheckbox: CheckBox
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
        kiraCheckbox = findViewById(R.id.kira_checkbox)
        hugoCheckbox = findViewById(R.id.hugo_checkbox)
        startButton = findViewById(R.id.start_button)
        
        // Set initial values
        poolSizeSeekBar.min = 2
        poolSizeSeekBar.max = 12
        poolSizeSeekBar.progress = 8 // Default to 8
        
        // Default to both contacts enabled
        kiraCheckbox.isChecked = true
        hugoCheckbox.isChecked = true
        
        updatePoolSizeLabel()
    }
    
    private fun loadExistingConfiguration() {
        val existingConfig = configPrefs.getConfiguration()
        if (existingConfig != null) {
            Log.i(TAG, "Loading existing configuration: $existingConfig")
            poolSizeSeekBar.progress = existingConfig.poolSize
            kiraCheckbox.isChecked = existingConfig.kiraEnabled
            hugoCheckbox.isChecked = existingConfig.hugoEnabled
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
        
        kiraCheckbox.setOnCheckedChangeListener { _, _ ->
            updatePoolSizeLabel()
        }
        
        hugoCheckbox.setOnCheckedChangeListener { _, _ ->
            updatePoolSizeLabel()
        }
        
        startButton.setOnClickListener {
            if (validateSelection()) {
                startSessionPools()
            }
        }
    }
    
    private fun updatePoolSizeLabel() {
        val size = poolSizeSeekBar.progress
        val activeContacts = mutableListOf<String>()
        if (kiraCheckbox.isChecked) activeContacts.add("Kira")
        if (hugoCheckbox.isChecked) activeContacts.add("Hugo")
        
        val totalSessions = size * activeContacts.size
        poolSizeLabel.text = "Pool Size: $size per contact (Total: $totalSessions sessions)"
    }
    
    private fun validateSelection(): Boolean {
        if (!kiraCheckbox.isChecked && !hugoCheckbox.isChecked) {
            Toast.makeText(this, "Please select at least one contact", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }
    
    private fun startSessionPools() {
        val poolSize = poolSizeSeekBar.progress
        val kiraEnabled = kiraCheckbox.isChecked
        val hugoEnabled = hugoCheckbox.isChecked
        
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
        
        Toast.makeText(this, "Configuration saved! Starting session pools...", Toast.LENGTH_SHORT).show()
        
        // Navigate to ContactSelectionActivity
        val intent = Intent(this, ContactSelectionActivity::class.java)
        startActivity(intent)
        finish()
    }
}