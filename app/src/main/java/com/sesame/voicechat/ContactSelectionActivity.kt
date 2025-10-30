package com.sesame.voicechat

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import kotlinx.coroutines.*
import java.util.*

class ContactSelectionActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ContactSelectionActivity"
    }
    
    private lateinit var greetingText: TextView
    
    // Character selection UI components
    private lateinit var kiraCharacterCard: CardView
    private lateinit var hugoCharacterCard: CardView
    private lateinit var kiraSelectedIndicator: TextView
    private lateinit var hugoSelectedIndicator: TextView
    
    // Session pool status UI components
    private lateinit var kiraPoolStatus: TextView
    private lateinit var hugoPoolStatus: TextView
    
    
    // Selection state
    private var selectedCharacter: String = "Kira" // Default to Kira
    
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
        
        // Character selection UI components
        kiraCharacterCard = findViewById(R.id.kiraCharacterCard)
        hugoCharacterCard = findViewById(R.id.hugoCharacterCard)
        kiraSelectedIndicator = findViewById(R.id.kiraSelectedIndicator)
        hugoSelectedIndicator = findViewById(R.id.hugoSelectedIndicator)
        
        // Session pool status UI components
        kiraPoolStatus = findViewById(R.id.kiraPoolStatus)
        hugoPoolStatus = findViewById(R.id.hugoPoolStatus)
        
        // Set initial selection state
        updateCharacterSelection()
    }
    
    private fun setupGreeting() {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (currentHour) {
            in 5..11 -> "Morning"
            in 12..17 -> "Afternoon"
            else -> "Evening"
        }
        
        greetingText.text = greeting
    }
    
    private fun setupClickListeners() {
        kiraCharacterCard.setOnClickListener {
            selectedCharacter = "Kira"
            updateCharacterSelection()
            startVoiceChat()
        }
        
        hugoCharacterCard.setOnClickListener {
            selectedCharacter = "Hugo"
            updateCharacterSelection()
            startVoiceChat()
        }
    }
    
    private fun updateCharacterSelection() {
        when (selectedCharacter) {
            "Kira" -> {
                kiraSelectedIndicator.visibility = android.view.View.VISIBLE
                hugoSelectedIndicator.visibility = android.view.View.INVISIBLE
                kiraCharacterCard.alpha = 1.0f
                hugoCharacterCard.alpha = 0.7f
            }
            "Hugo" -> {
                kiraSelectedIndicator.visibility = android.view.View.INVISIBLE
                hugoSelectedIndicator.visibility = android.view.View.VISIBLE
                kiraCharacterCard.alpha = 0.7f
                hugoCharacterCard.alpha = 1.0f
            }
        }
    }
    
    
    private fun setupSessionManager() {
        application = getApplication() as SesameApplication
    }
    
    private fun initializeSessionPoolsIfNeeded() {
        // Check if we have a saved configuration
        if (!application.hasConfiguration()) {
            // No configuration - redirect to configuration screen
            val intent = Intent(this, ConfigurationActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        
        // Configuration exists, but don't auto-start session pools
        // They should only start when user clicks "START SESSION POOLS" in configuration
        // This allows the user to modify configuration before starting pools
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
        
        // Update French-only session pools
        updatePoolStatus("Kira-FR", kiraPoolStatus, availableContacts)
        updatePoolStatus("Hugo-FR", hugoPoolStatus, availableContacts)
    }
    
    private fun updatePoolStatus(contactKey: String, statusView: TextView, availableContacts: List<String>) {
        try {
            if (contactKey in availableContacts) {
                val sessionManager = application.getSessionManagerForContact(contactKey)
                val progressInfo = sessionManager.getSessionProgress()
                val allSessionsInfo = sessionManager.getAllSessionsProgress()
                
                val availableCount = allSessionsInfo.count { it.isConnected }
                val readyCount = allSessionsInfo.count { it.isComplete }
                
                val mostAdvancedProgress = if (progressInfo != null) {
                    val (progress, _) = progressInfo
                    (progress * 100).toInt()
                } else {
                    0
                }
                
                // Format display name (e.g., "Kira-FR" -> "Kira (Français)")
                val displayName = contactKey.replace("-FR", " (Français)")
                statusView.text = "$displayName: ${availableCount} available, ${readyCount} ready, ${mostAdvancedProgress}% progress"
                statusView.alpha = 1.0f
                
            } else {
                val displayName = contactKey.replace("-FR", " (Français)")
                statusView.text = "$displayName: Not configured"
                statusView.alpha = 0.5f
            }
        } catch (e: Exception) {
            val displayName = contactKey.replace("-FR", " (Français)")
            statusView.text = "$displayName: Error"
            statusView.alpha = 0.5f
        }
    }
    
    private fun startVoiceChat() {
        // Always use French
        val selectedLanguage = "FR"
        
        // Create French contact key
        val languageSpecificContactKey = "${selectedCharacter}-FR"
        
        // Verify the specific character-language session pool is available before starting
        val availableContacts = application.getAvailableContacts()
        if (languageSpecificContactKey !in availableContacts) {
            Toast.makeText(this, "Session not ready for $selectedCharacter, please wait...", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("CONTACT_NAME", selectedCharacter)
            putExtra("LANGUAGE", selectedLanguage)
            putExtra("CONTACT_KEY", languageSpecificContactKey) // e.g., "Kira-EN", "Hugo-FR"
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