package com.sriox.vasateysec

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.databinding.ActivitySettingsBinding
import com.sriox.vasateysec.services.BleGuardianService
import com.sriox.vasateysec.utils.LlamaBridge
import com.sriox.vasateysec.utils.SmolLM2Helper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.NullPointerException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

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
        setupLocalModelDownload()
    }

    private fun setupHeader() {
        binding.backButton.setOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        // Core features from local prefs
        binding.switchPhotoCapture.isChecked = prefs.getBoolean("photo_capture_enabled", true)
        binding.switchLocationTracking.isChecked = prefs.getBoolean("location_tracking_enabled", true)
        binding.switchVoiceAlert.isChecked = prefs.getBoolean("voice_alert_enabled", false)
        binding.switchAutoCall.isChecked = prefs.getBoolean("auto_call_enabled", false)
        binding.switchVibration.isChecked = prefs.getBoolean("vibration_enabled", true)
        binding.switchSound.isChecked = prefs.getBoolean("sound_enabled", true)
        binding.switchLoudAlarm.isChecked = prefs.getBoolean("loud_alarm_enabled", true) // ON by default!
        
        binding.switchDoubleWord.isChecked = prefs.getBoolean("double_word_enabled", true)

        // Wake word from local prefs
        val vasateyPrefs = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)
        val wakeWord = vasateyPrefs.getString("wake_word", "help me")
        binding.currentWakeWord.text = "Current: $wakeWord"
    }

    private fun setupSwitches() {
        binding.switchPhotoCapture.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("photo_capture_enabled", isChecked).apply()
        }
        binding.switchLocationTracking.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("location_tracking_enabled", isChecked).apply()
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
    
    private fun setupLocalModelDownload() {
        val modelFile = SmolLM2Helper.getModelFile(this)

        // Delete incomplete downloads (<150MB)
        if (modelFile.exists() && modelFile.length() < 150_000_000L) {
            modelFile.delete()
        }

        // Initial UI state
        if (SmolLM2Helper.isModelDownloaded(this)) {
            binding.btnDownloadLocalModel.text = "SmolLM2 360M Model Ready"
            binding.btnDownloadLocalModel.isEnabled = true
            binding.btnDownloadLocalModel.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_delete)
            binding.tvModelDownloadStatus.text = "Status: SmolLM2 360M Ready (${modelFile.name}, ${modelFile.length() / (1024 * 1024)} MB)"
            
            // Delete logic
            binding.btnDownloadLocalModel.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete SmolLM2 Model")
                    .setMessage("Delete the on-device SmolLM2 360M AI model (~229 MB)?")
                    .setPositiveButton("Delete") { _, _ ->
                        LlamaBridge.unloadModel()
                        modelFile.delete()
                        showToast("SmolLM2 model deleted")
                        setupLocalModelDownload()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            return
        } else {
            binding.btnDownloadLocalModel.text = "Download SmolLM2 360M Model"
            binding.btnDownloadLocalModel.isEnabled = true
            binding.btnDownloadLocalModel.icon = ContextCompat.getDrawable(this, android.R.drawable.stat_sys_download)
            binding.tvModelDownloadStatus.text = "Not downloaded — tap to download SmolLM2 360M (~229 MB)"
        }

        binding.btnDownloadLocalModel.setOnClickListener {
            val urlString = SmolLM2Helper.DOWNLOAD_URL
            val tempFile = File(getExternalFilesDir(null), "${SmolLM2Helper.MODEL_FILE_NAME}.tmp")
            
            binding.btnDownloadLocalModel.isEnabled = false
            binding.modelDownloadProgress.visibility = View.VISIBLE
            binding.modelDownloadProgress.isIndeterminate = false
            binding.modelDownloadProgress.progress = 0
            binding.tvModelDownloadStatus.text = "Connecting to server..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.MINUTES)
                        .build()

                    val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
                    val activeToken = if (SmolLM2Helper.HARDCODED_HF_TOKEN.isNotBlank()) {
                        SmolLM2Helper.HARDCODED_HF_TOKEN
                    } else {
                        prefs.getString("hf_token", null)?.trim()
                    }

                    val reqBuilder = Request.Builder()
                        .url(urlString)
                        .addHeader("Accept", "application/octet-stream")
                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    
                    if (!activeToken.isNullOrEmpty()) {
                        reqBuilder.addHeader("Authorization", "Bearer $activeToken")
                    }
                    val request = reqBuilder.build()

                    val response = client.newCall(request).execute()

                    Log.d("ModelDownload", "HTTP Response Code: ${response.code}")
                    Log.d("ModelDownload", "Content-Type: ${response.header("Content-Type")}")
                    Log.d("ModelDownload", "Content-Length: ${response.header("Content-Length")}")

                    if (response.code == 401 || response.code == 403) {
                        throw IOException("HTTP ${response.code}: Gated or unauthorized request.")
                    }
                    if (!response.isSuccessful) throw IOException("HTTP error code: ${response.code}")

                    val body = response.body ?: throw NullPointerException("Empty response body")
                    val contentType = response.header("Content-Type") ?: ""
                    val contentLength = body.contentLength()
                    
                    // Validation: must be binary model, NOT text/html
                    if (contentType.contains("text/html", ignoreCase = true)) {
                        throw IOException("Server returned web page instead of model binary.")
                    }

                    var bytesCopied: Long = 0
                    val buffer = ByteArray(64 * 1024) // 64KB buffer
                    var lastUpdateTime = System.currentTimeMillis()

                    if (tempFile.exists()) tempFile.delete()

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            var bytes = input.read(buffer)
                            while (bytes >= 0) {
                                output.write(buffer, 0, bytes)
                                bytesCopied += bytes
                                
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastUpdateTime > 250) {
                                    val progress = if (contentLength > 0) ((bytesCopied * 100) / contentLength).toInt() else 0
                                    val mbDownloaded = bytesCopied / (1024 * 1024)
                                    val totalMb = if (contentLength > 0) contentLength / (1024 * 1024) else SmolLM2Helper.ESTIMATED_SIZE_MB
                                    
                                    launch(Dispatchers.Main) {
                                        binding.modelDownloadProgress.progress = progress
                                        binding.tvModelDownloadStatus.text = "Downloading SmolLM2 360M... $mbDownloaded MB / $totalMb MB ($progress%)"
                                    }
                                    lastUpdateTime = currentTime
                                }
                                bytes = input.read(buffer)
                            }
                        }
                    }

                    // Atomic rename from .tmp to final model file
                    if (tempFile.exists() && tempFile.length() > 180_000_000L) {
                        if (modelFile.exists()) modelFile.delete()
                        tempFile.renameTo(modelFile)
                        // Warm up in memory
                        SmolLM2Helper.warmUpModel(this@SettingsActivity)
                    } else {
                        throw IOException("Downloaded file is incomplete (${tempFile.length()} bytes)")
                    }

                    launch(Dispatchers.Main) {
                        showToast("SmolLM2 360M ready! Offline AI chat and emergency SMS summarizer active.")
                        binding.modelDownloadProgress.visibility = View.GONE
                        setupLocalModelDownload() // Refresh UI
                    }

                } catch (e: Exception) {
                    Log.e("ModelDownload", "Download failed: ${e.message}", e)
                    if (tempFile.exists()) tempFile.delete()

                    launch(Dispatchers.Main) {
                        binding.modelDownloadProgress.visibility = View.GONE
                        val msg = e.localizedMessage ?: "download failed"
                        binding.tvModelDownloadStatus.text = "Download failed: $msg"
                        binding.btnDownloadLocalModel.isEnabled = true
                        if (msg.contains("401") || msg.contains("403")) {
                            promptForHfToken()
                        }
                    }
                }
            }
        }
    }

    private fun promptForHfToken() {
        val input = android.widget.EditText(this).apply {
            hint = "hf_xxxxxxxx (read token)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Model needs access")
            .setMessage("This file needs a HuggingFace login. Create a read token at huggingface.co/settings/tokens, paste it here, then tap Download again.\n\nOr side-load: adb push your model file /storage/emulated/0/Android/data/com.sriox.vasateysec/files/")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isNotEmpty()) {
                    getSharedPreferences("vasatey_settings", MODE_PRIVATE).edit().putString("hf_token", token).apply()
                    showToast("Token saved — tap Download again")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val trimmed = wakeWord.trim().lowercase()
        getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit().putString("wake_word", trimmed).apply()
        binding.currentWakeWord.text = "Current: $trimmed"
        showToast("Wake word updated to '$trimmed'")
        val serviceIntent = Intent(this@SettingsActivity, VoskWakeWordService::class.java)
        stopService(serviceIntent)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startService(serviceIntent) }, 500)
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener { startActivity(Intent(this, AddGuardianActivity::class.java)); finish() }
        navHistory?.setOnClickListener { startActivity(Intent(this, AlertHistoryActivity::class.java)); finish() }
        sosButton?.setOnClickListener { com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this) }
        navGhistory?.setOnClickListener { startActivity(Intent(this, GuardianMapActivity::class.java)); finish() }
        navProfile?.setOnClickListener { startActivity(Intent(this, EditProfileActivity::class.java)); finish() }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
