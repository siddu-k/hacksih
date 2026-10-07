package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sriox.vasateysec.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)

        setupHeader()
        loadSettings()
        setupSwitches()
        setupWakeWordCard()
        setupBottomNavigation()
    }

    private fun setupHeader() {
        binding.backButton.setOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        // Core features from local prefs
        binding.switchPhotoCapture.isChecked = prefs.getBoolean("photo_capture_enabled", true)
        binding.switchVoiceAlert.isChecked = prefs.getBoolean("voice_alert_enabled", false)
        binding.switchAutoCall.isChecked = prefs.getBoolean("auto_call_enabled", false)
        binding.switchVibration.isChecked = prefs.getBoolean("vibration_enabled", true)
        binding.switchSound.isChecked = prefs.getBoolean("sound_enabled", true)
        binding.switchLoudAlarm.isChecked = prefs.getBoolean("loud_alarm_enabled", true) // ON by default!
        
        binding.switchDoubleWord.isChecked = prefs.getBoolean("double_word_enabled", true)

        // Wake word from local prefs (language-aware default: English "help me" / Telugu "సహాయం")
        val vasateyPrefs = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)
        val lang = com.sriox.vasateysec.utils.VoiceLanguage.get(this)
        val wakeWord = vasateyPrefs.getString(
            "wake_word",
            com.sriox.vasateysec.utils.VoiceLanguage.defaultWakeWord(lang)
        )
        binding.currentWakeWord.text = "Current ($lang): $wakeWord"
    }

    private fun setupSwitches() {
        binding.switchPhotoCapture.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("photo_capture_enabled", isChecked).apply()
        }
        binding.switchVoiceAlert.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("voice_alert_enabled", isChecked).apply()
            getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit()
                .putBoolean("voice_alert_enabled", isChecked)
                .apply()
        }
        binding.switchAutoCall.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("auto_call_enabled", isChecked).apply()
        }
        binding.switchVibration.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("vibration_enabled", isChecked).apply()
        }
        binding.switchSound.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("sound_enabled", isChecked).apply()
        }
        binding.switchLoudAlarm.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("loud_alarm_enabled", isChecked).apply()
            showToast(if (isChecked) "Loud Emergency Siren enabled" else "Loud Emergency Siren disabled")
        }
        binding.switchDoubleWord.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("double_word_enabled", isChecked).apply()
            showToast(if (isChecked) "Double trigger active" else "Single trigger active")
        }
    }
    
    private fun setupWakeWordCard() {
        binding.wakeWordCard.setOnClickListener {
            showWakeWordDialog()
        }
    }
    
    private fun showWakeWordDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Enter new wake word"
        android.app.AlertDialog.Builder(this)
            .setTitle("Change Wake Word")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newWakeWord = input.text.toString().trim()
                if (newWakeWord.isNotEmpty()) saveWakeWord(newWakeWord)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveWakeWord(wakeWord: String) {
        val lang = com.sriox.vasateysec.utils.VoiceLanguage.get(this)
        val trimmed = com.sriox.vasateysec.utils.VoiceLanguage.normalizeWakeWord(wakeWord, lang)
        if (trimmed.isEmpty()) return
        getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit().putString("wake_word", trimmed).apply()
        binding.currentWakeWord.text = "Current ($lang): $trimmed"
        showToast("Wake word updated to '$trimmed'")
        val serviceIntent = Intent(this@SettingsActivity, VoskWakeWordService::class.java)
        stopService(serviceIntent)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startService(serviceIntent) }, 500)
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener { startActivity(Intent(this, AddGuardianActivity::class.java)); finish() }
        navHistory?.setOnClickListener { startActivity(Intent(this, AlertHistoryActivity::class.java)); finish() }
        sosButton?.setOnClickListener { com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this) }
        navProfile?.setOnClickListener { startActivity(Intent(this, EditProfileActivity::class.java)); finish() }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
