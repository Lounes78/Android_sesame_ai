package com.sesame.voicechat

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import kotlinx.coroutines.*
import java.util.*

class ContactSelectionActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ContactSelectionActivity"
    }
    
    private lateinit var greetingText: TextView
    
    // Kira UI components
    private lateinit var kiraConnectButton: CardView
    private lateinit var kiraConnectionStatusText: TextView
    private lateinit var kiraPoolStatusText: TextView
    
    // Hugo UI components
    private lateinit var hugoConnectButton: CardView
    private lateinit var hugoConnectionStatusText: TextView
    private lateinit var hugoPoolStatusText: TextView
    
    private lateinit var application: SesameApplication
    private var progressUpdateJob: Job? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_selection)
        
        initializeViews()
        setupGreeting()
        setupClickListeners()
        setupSessionManager()
        initializeSessionPoolsIfNeeded()
        startProgressTracking()
    }
    
    private fun initializeViews() {
        greetingText = findViewById(R.id.greetingText)
        
        // Kira UI components
        kiraConnectButton = findViewById(R.id.kiraConnectButton)
        kiraConnectionStatusText = findViewById(R.id.kiraConnectionStatusText)
        kiraPoolStatusText = findViewById(R.id.kiraPoolStatusText)
        
        // Hugo UI components
        hugoConnectButton = findViewById(R.id.hugoConnectButton)
        hugoConnectionStatusText = findViewById(R.id.hugoConnectionStatusText)
        hugoPoolStatusText = findViewById(R.id.hugoPoolStatusText)
    }
    
    private fun setupGreeting() {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (currentHour) {
            in 5..11 -> "Morning"
            in 12..17 -> "Afternoon"
            else -> "Evening"
        }
        
        greetingText.text = "$greeting, kira"
    }
    
    private fun setupClickListeners() {
        kiraConnectButton.setOnClickListener {
            startVoiceChat("Kira")
        }
        
        hugoConnectButton.setOnClickListener {
            startVoiceChat("Hugo")
        }
    }
    
    private fun setupSessionManager() {
        application = getApplication() as SesameApplication
    }
    
    private fun initializeSessionPoolsIfNeeded() {
        // Check if session pools are already running
        val availableContacts = application.getAvailableContacts()
        if (availableContacts.isNotEmpty()) {
            return // Already initialized
        }
        
        // Check if we have a saved configuration
        if (!application.hasConfiguration()) {
            // No configuration - redirect to configuration screen
            val intent = Intent(this, ConfigurationActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        
        // We have configuration but no running session pools - initialize them
        val configPrefs = ConfigurationPreferences(this)
        val config = configPrefs.getConfiguration()
        if (config != null) {
            application.initializeWithConfiguration(config)
        }
    }
    
    private fun startProgressTracking() {
        progressUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                try {
                    updateSessionProgress()
                    delay(500) // Update every 500ms for smooth progress
                } catch (e: Exception) {
                    // Ignore errors during progress tracking
                }
            }
        }
    }
    
    private fun updateSessionProgress() {
        val availableContacts = application.getAvailableContacts()
        
        // Update Kira status if available
        if ("Kira" in availableContacts) {
            updateContactProgress("Kira", kiraConnectionStatusText, kiraPoolStatusText)
        } else {
            kiraConnectionStatusText.text = "No tokens available"
            kiraPoolStatusText.text = "Kira: Not configured"
            kiraConnectButton.alpha = 0.5f
            kiraConnectButton.isClickable = false
        }
        
        // Update Hugo status if available
        if ("Hugo" in availableContacts) {
            updateContactProgress("Hugo", hugoConnectionStatusText, hugoPoolStatusText)
        } else {
            hugoConnectionStatusText.text = "No tokens available"
            hugoPoolStatusText.text = "Hugo: Not configured"
            hugoConnectButton.alpha = 0.5f
            hugoConnectButton.isClickable = false
        }
    }
    
    private fun updateContactProgress(contactName: String, statusText: TextView, poolText: TextView) {
        try {
            val sessionManager = application.getSessionManagerForContact(contactName)
            
            // Get session progress info
            val progressInfo = sessionManager.getSessionProgress()
            val poolStatus = sessionManager.getPoolStatus()
            
            // Update connection status based on best session
            val connectionStatus = if (progressInfo != null) {
                val (progress, isComplete) = progressInfo
                val progressPercent = (progress * 100).toInt()
                when {
                    isComplete -> "Ready to connect!"
                    progressPercent > 0 -> "Preparing... ${progressPercent}%"
                    else -> "Getting ready..."
                }
            } else {
                "Initializing sessions..."
            }
            statusText.text = connectionStatus
            
            // Update pool status
            poolText.text = poolStatus
            
        } catch (e: Exception) {
            statusText.text = "Error loading status"
            poolText.text = "$contactName: Error"
        }
    }
    
    private fun startVoiceChat(contactName: String) {
        // Verify contact is available before starting
        val availableContacts = application.getAvailableContacts()
        if (contactName !in availableContacts) {
            return // Contact not available, button should be disabled
        }
        
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("CONTACT_NAME", contactName)
        }
        startActivity(intent)
        
        // Add smooth transition animation
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
    
    override fun onResume() {
        super.onResume()
        // Update greeting when returning to this screen
        setupGreeting()
        
        // Restart progress tracking when resuming
        if (progressUpdateJob?.isActive != true) {
            startProgressTracking()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Stop progress tracking when paused to save resources
        progressUpdateJob?.cancel()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        progressUpdateJob?.cancel()
    }
}