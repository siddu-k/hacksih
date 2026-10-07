package com.sriox.vasateysec

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.databinding.ActivityEditProfileBinding
import com.sriox.vasateysec.utils.SessionManager
import com.sriox.vasateysec.utils.VoiceLanguage
import com.sriox.vasateysec.utils.VoskModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        setupLanguageDropdown()
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

        val wakeWord = prefs.getString("wake_word", VoiceLanguage.defaultWakeWord(VoiceLanguage.get(this)))
        binding.wakeWordInput.setText(wakeWord)
        refreshTeluguStatus()
    }

    private fun setupLanguageDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, VoiceLanguage.DISPLAY_NAMES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = adapter
        val current = VoiceLanguage.get(this)
        binding.languageSpinner.setSelection(VoiceLanguage.CODES.indexOf(current).coerceAtLeast(0), false)

        binding.languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = VoiceLanguage.CODES.getOrElse(position) { VoiceLanguage.EN }
                if (picked == VoiceLanguage.get(this@EditProfileActivity)) return
                onLanguagePicked(picked)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun onLanguagePicked(lang: String) {
        VoiceLanguage.set(this, lang)
        // Auto-fill the matching default wake word (user can still type their own).
        binding.wakeWordInput.setText(VoiceLanguage.defaultWakeWord(lang))
        if (lang == VoiceLanguage.TE && !VoskModelManager.isTeluguReady(this)) {
            downloadTeluguModel()
        } else {
            refreshTeluguStatus()
            restartVoiceService()
            Toast.makeText(
                this,
                if (lang == VoiceLanguage.TE) "Voice language: Telugu" else "Voice language: English",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun refreshTeluguStatus() {
        if (VoiceLanguage.get(this) != VoiceLanguage.TE) {
            binding.tvTeluguModelStatus.visibility = View.GONE
            return
        }
        binding.tvTeluguModelStatus.visibility = View.VISIBLE
        binding.tvTeluguModelStatus.text = if (VoskModelManager.isTeluguReady(this))
            "Telugu voice model: ready on this phone"
        else
            "Telugu voice model: not downloaded"
    }

    private fun downloadTeluguModel() {
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(48, 32, 48, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Telugu voice model (~40 MB)")
            .setMessage("Downloading once — after this, Telugu detection works fully offline.")
            .setView(progressBar)
            .setCancelable(false)
            .create()
        dialog.show()
        Toast.makeText(this, "Downloading Telugu model — use WiFi if possible", Toast.LENGTH_LONG).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val ok = VoskModelManager.downloadTeluguModel(this@EditProfileActivity) { p ->
                runOnUiThread {
                    when (p) {
                        is VoskModelManager.TeProgress.Download -> {
                            if (p.totalMb > 0) {
                                val pct = ((p.doneMb * 100) / p.totalMb).toInt().coerceIn(0, 100)
                                progressBar.progress = pct
                                dialog.setMessage("Downloading… ${p.doneMb} / ${p.totalMb} MB ($pct%)")
                            } else {
                                dialog.setMessage("Downloading… ${p.doneMb} MB")
                            }
                        }
                        VoskModelManager.TeProgress.Unzipping ->
                            dialog.setMessage("Unpacking model…")
                    }
                }
            }
            withContext(Dispatchers.Main) {
                try { dialog.dismiss() } catch (_: Exception) {}
                refreshTeluguStatus()
                if (ok) {
                    Toast.makeText(this@EditProfileActivity, "Telugu ready! Say your Telugu wake word.", Toast.LENGTH_LONG).show()
                    restartVoiceService()
                } else {
                    // Fall back so detection keeps working in English.
                    VoiceLanguage.set(this@EditProfileActivity, VoiceLanguage.EN)
                    binding.languageSpinner.setSelection(0)
                    binding.wakeWordInput.setText(VoiceLanguage.DEFAULT_EN_WAKE)
                    refreshTeluguStatus()
                    Toast.makeText(this@EditProfileActivity, "Download failed — kept English. Retry on WiFi.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Reload the voice engine so a new language / wake word takes effect now. */
    private fun restartVoiceService() {
        try {
            if (!prefs.getBoolean("voice_alert_enabled", false)) return
            val svc = Intent(this, VoskWakeWordService::class.java)
            stopService(svc)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (_: Exception) {}
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
        prefs.edit().putString("wake_word", wakeWord.replace("\"", "").lowercase()).apply()

        Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
        restartVoiceService()
        finish()
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
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
        navProfile?.setOnClickListener { /* Already here */ }
    }
}
