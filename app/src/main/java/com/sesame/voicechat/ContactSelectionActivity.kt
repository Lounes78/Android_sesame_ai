package com.sesame.voicechat

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import java.util.*

class ContactSelectionActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ContactSelectionActivity"
    }
    
    private lateinit var greetingText: TextView
    private lateinit var mayaCard: CardView
    private lateinit var milesCard: CardView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_selection)
        
        initializeViews()
        setupGreeting()
        setupClickListeners()
    }
    
    private fun initializeViews() {
        greetingText = findViewById(R.id.greetingText)
        mayaCard = findViewById(R.id.mayaCard)
        milesCard = findViewById(R.id.milesCard)
    }
    
    private fun setupGreeting() {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (currentHour) {
            in 5..11 -> "Morning"
            in 12..17 -> "Afternoon"
            else -> "Evening"
        }
        
        // You can customize the name here or get it from user preferences
        greetingText.text = "$greeting, kira"
    }
    
    private fun setupClickListeners() {
        mayaCard.setOnClickListener {
            startVoiceChat("Maya")
        }
        
        milesCard.setOnClickListener {
            startVoiceChat("Miles")
        }
    }
    
    private fun startVoiceChat(character: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("CHARACTER_NAME", character)
        }
        startActivity(intent)
        
        // Add smooth transition animation
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
    
    override fun onResume() {
        super.onResume()
        // Update greeting when returning to this screen
        setupGreeting()
    }
}