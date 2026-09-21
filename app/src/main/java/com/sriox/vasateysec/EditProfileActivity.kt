package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sriox.vasateysec.databinding.ActivityEditProfileBinding
import com.sriox.vasateysec.utils.SessionManager

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)

        setupHeader()
        loadProfile()
        setupBottomNavigation()

        binding.saveButton.setOnClickListener {
            val name = binding.nameInput.text.toString().trim()
            val phone = binding.phoneInput.text.toString().trim()
            val wakeWord = binding.wakeWordInput.text.toString().trim()

            if (validateInputs(name, phone, wakeWord)) {
                updateProfileLocal(name, phone, wakeWord)
            }
        }

        binding.logoutButton.setOnClickListener {
            logoutUser()
        }
    }

    private fun logoutUser() {
        SessionManager.clearSession()
        val intent = Intent(this@EditProfileActivity, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setupHeader() {
        binding.backButton.setOnClickListener {
            finish()
        }
    }

    private fun loadProfile() {
        binding.nameInput.setText(SessionManager.getUserName() ?: "")
        binding.emailInput.setText(SessionManager.getUserEmail() ?: "")
        binding.phoneInput.setText(SessionManager.getUserPhone() ?: "")
        
        val wakeWord = prefs.getString("wake_word", "help me")
        binding.wakeWordInput.setText(wakeWord)
    }

    private fun validateInputs(name: String, phone: String, wakeWord: String): Boolean {
        if (name.isEmpty()) {
            binding.nameInputLayout.error = "Name is required"
            return false
        }
        if (phone.isEmpty()) {
            binding.phoneInputLayout.error = "Phone number is required"
            return false
        }
        if (wakeWord.isEmpty() || wakeWord.length < 3) {
            binding.wakeWordInputLayout.error = "Wake word must be at least 3 characters"
            return false
        }
        return true
    }

    private fun updateProfileLocal(name: String, phone: String, wakeWord: String) {
        SessionManager.updateUserName(name)
        SessionManager.updateUserPhone(phone)
        prefs.edit().putString("wake_word", wakeWord.lowercase()).apply()

        Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener {
            val intent = Intent(this, AddGuardianActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        navHistory?.setOnClickListener {
            val intent = Intent(this, AlertHistoryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }
        navGhistory?.setOnClickListener {
            val intent = Intent(this, GuardianMapActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        navProfile?.setOnClickListener { /* Already here */ }
    }
}
