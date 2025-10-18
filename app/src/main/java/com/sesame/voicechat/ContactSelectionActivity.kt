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
    private lateinit var mayaConnectButton: CardView
    private lateinit var connectionStatusText: TextView
    private lateinit var poolStatusText: TextView
    private lateinit var sessionManager: SessionManager
    private var progressUpdateJob: Job? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_selection)
        
        initializeViews()
        setupGreeting()
        setupClickListeners()
        setupSessionManager()
        startProgressTracking()
    }
    
    private fun initializeViews() {
        greetingText = findViewById(R.id.greetingText)
        mayaConnectButton = findViewById(R.id.mayaConnectButton)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        poolStatusText = findViewById(R.id.poolStatusText)
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
        mayaConnectButton.setOnClickListener {
            startVoiceChat("Maya")
        }
    }
    
    private fun setupSessionManager() {
        sessionManager = (application as SesameApplication).sessionManager
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
        connectionStatusText.text = connectionStatus
        
        // Update pool status
        poolStatusText.text = poolStatus
    }
    
    private fun startVoiceChat(character: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("CONTACT_NAME", character)
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