package com.sriox.vasateysec

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationView
import com.sriox.vasateysec.databinding.ActivityHomeBinding
import com.sriox.vasateysec.models.SmsContact
import com.sriox.vasateysec.services.BleGuardianService
import com.sriox.vasateysec.utils.AlertQueueManager
import com.sriox.vasateysec.utils.BottomNavHelper
import com.sriox.vasateysec.utils.NetworkMonitor
import com.sriox.vasateysec.utils.SOSHelper
import com.sriox.vasateysec.utils.SessionManager
import com.sriox.vasateysec.utils.SmsHelper
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityHomeBinding
    private var smsContacts = listOf<SmsContact>()
    private var isSosActive = false
    
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimerDisplay()
            timerHandler.postDelayed(this, 1000)
        }
    }

    private var cancelCountDownTimer: CountDownTimer? = null

    private val watchStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(BleGuardianService.EXTRA_STATUS)
            Log.d("HomeActivity", "Received Watch Status: $status")
            updateHardwareStatusUi(status)
        }
    }

    private val alertTriggeredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("HomeActivity", "SOS Alert triggered: Auto-opening Cancel Alert timer (40s)")
            startCancelAlertTimer(40)
        }
    }

    private val queueUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread { updateQueueBadgeUi() }
        }
    }

    companion object {
        const val ACTION_ALERT_TRIGGERED = "com.sriox.vasateysec.ALERT_TRIGGERED"
        private const val RECORD_AUDIO_PERMISSION_CODE = 123
        private const val ALL_PERMISSIONS_CODE = 126
        private const val PREF_SOS_ACTIVE = "is_sos_active_persistent"
        private const val OVERLAY_PERMISSION_REQ_CODE = 127
    }

    /**
     * Auto-opens the Cancel Alert button with a 40-second countdown timer.
     * User can tap without entering any password to notify guardians that they are safe.
     * Hides automatically after 40 seconds.
     */
    fun startCancelAlertTimer(durationSeconds: Int = 40) {
        cancelCountDownTimer?.cancel()
        binding.cardCancelAlert.visibility = View.VISIBLE
        binding.btnCancelAlert.isEnabled = true
        binding.btnCancelAlert.text = "CANCEL ALERT (I AM SAFE)"
        binding.tvCancelCountdown.text = "${durationSeconds}s"

        cancelCountDownTimer = object : CountDownTimer(durationSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L).coerceAtLeast(1)
                binding.tvCancelCountdown.text = "${sec}s"
            }

            override fun onFinish() {
                binding.cardCancelAlert.visibility = View.GONE
            }
        }.start()

        binding.btnCancelAlert.setOnClickListener {
            cancelCountDownTimer?.cancel()
            binding.btnCancelAlert.isEnabled = false
            binding.btnCancelAlert.text = "CANCELLING..."
            lifecycleScope.launch {
                SmsHelper.sendCancelAlertSms(this@HomeActivity)
                Toast.makeText(this@HomeActivity, "Alert Cancelled: I Am Safe SMS sent to all guardians", Toast.LENGTH_LONG).show()
                binding.cardCancelAlert.visibility = View.GONE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        isSosActive = prefs.getBoolean(PREF_SOS_ACTIVE, false)

        setupQuickActions()
        setupBottomNavigation()
        BottomNavHelper.highlightActiveItem(this, BottomNavHelper.NavItem.NONE)
        
        ensureSessionValid()
        loadUserProfile()
        loadSmsContactsForSpinner()
        restoreVoiceAlertState()
        requestAllPermissions()
        checkOverlayPermission()
        
        if (isSosActive) timerHandler.post(timerRunnable)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Emergency Feature Required")
                    .setMessage("To allow the app to automatically call guardians when closed or locked, please enable 'Appear on top' permission.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BleGuardianService.ACTION_WATCH_STATUS)
        val alertFilter = IntentFilter(ACTION_ALERT_TRIGGERED)
        val queueFilter = IntentFilter(AlertQueueManager.ACTION_QUEUE_UPDATED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(watchStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(alertTriggeredReceiver, alertFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(queueUpdatedReceiver, queueFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(watchStatusReceiver, filter)
            registerReceiver(alertTriggeredReceiver, alertFilter)
            registerReceiver(queueUpdatedReceiver, queueFilter)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(watchStatusReceiver, filter)
        LocalBroadcastManager.getInstance(this).registerReceiver(alertTriggeredReceiver, alertFilter)
        LocalBroadcastManager.getInstance(this).registerReceiver(queueUpdatedReceiver, queueFilter)

        checkInitialHardwareStatus()

        // Start live mobile signal & cellular network monitoring
        NetworkMonitor.startMonitoring(this) { signalInfo ->
            runOnUiThread { updateSignalMeterUi(signalInfo) }
        }
        updateQueueBadgeUi()
    }

    override fun onResume() {
        super.onResume()
        loadUserProfile()
        loadSmsContactsForSpinner()
        updateQueueBadgeUi()
    }

    override fun onStop() {
        super.onStop()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(watchStatusReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(alertTriggeredReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(queueUpdatedReceiver)
            unregisterReceiver(watchStatusReceiver)
            unregisterReceiver(alertTriggeredReceiver)
            unregisterReceiver(queueUpdatedReceiver)
        } catch (e: Exception) {}
        NetworkMonitor.stopMonitoring(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCountDownTimer?.cancel()
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun loadSmsContactsForSpinner() {
        smsContacts = SmsHelper.getFromLocalStorage(this)
        if (smsContacts.isNotEmpty()) {
            val contactNames = smsContacts.map { it.name }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, contactNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerCallRecipient.adapter = adapter
            val savedPhone = getSharedPreferences("alert_settings", MODE_PRIVATE).getString("auto_call_recipient", null)
            val index = smsContacts.indexOfFirst { it.phone == savedPhone }
            if (index >= 0) binding.spinnerCallRecipient.setSelection(index)
        }
    }

    private fun checkInitialHardwareStatus() {
        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        if (prefs.getBoolean("hardware_sos_enabled", false)) {
            binding.hardwareStatusLayout.visibility = View.VISIBLE
            updateHardwareStatusUi(if (isSosActive) BleGuardianService.STATUS_SOS_ACTIVE else BleGuardianService.STATUS_DISCONNECTED)
        } else {
            binding.hardwareStatusLayout.visibility = View.GONE
        }
    }

    private fun updateHardwareStatusUi(status: String?) {
        binding.hardwareStatusLayout.visibility = View.VISIBLE
        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        
        // Clear SOS state on Safe (CONNECTED) or Disconnect
        if (isSosActive && (status == BleGuardianService.STATUS_CONNECTED || status == BleGuardianService.STATUS_DISCONNECTED)) {
            isSosActive = false
            prefs.edit().putBoolean(PREF_SOS_ACTIVE, false).apply()
            timerHandler.removeCallbacks(timerRunnable)
            binding.tvSmsTimer.visibility = View.GONE
        }

        when (status) {
            BleGuardianService.STATUS_CONNECTING -> {
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(if (isSosActive) Color.RED else Color.YELLOW)
                binding.hardwareStatusText.text = if (isSosActive) "SOS ACTIVE (Syncing...)" else "Syncing..."
            }
            BleGuardianService.STATUS_CONNECTED -> {
                if (!isSosActive) {
                    binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
                    binding.hardwareStatusText.text = "Watch Online"
                }
            }
            BleGuardianService.STATUS_SOS_ACTIVE -> {
                isSosActive = true
                prefs.edit().putBoolean(PREF_SOS_ACTIVE, true).apply()
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.RED)
                binding.hardwareStatusText.text = "SOS ACTIVE"
                timerHandler.post(timerRunnable)
            }
            BleGuardianService.STATUS_GPS_RECEIVED -> {
                if (!isSosActive) {
                    binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.BLUE)
                    binding.hardwareStatusText.text = "Location Fix"
                    binding.hardwareStatusDot.postDelayed({ if (!isSosActive) updateHardwareStatusUi(BleGuardianService.STATUS_CONNECTED) }, 1500)
                }
            }
            BleGuardianService.STATUS_DISCONNECTED -> {
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.GRAY)
                binding.hardwareStatusText.text = "Watch Offline"
            }
        }
    }
    
    private fun updateTimerDisplay() {
        val remainingMs = SmsHelper.getRemainingCooldownMs(this)
        if (remainingMs > 0 && isSosActive) {
            binding.tvSmsTimer.visibility = View.VISIBLE
            val secondsTotal = remainingMs / 1000
            binding.tvSmsTimer.text = String.format("Next alert in: %02d:%02d", secondsTotal / 60, secondsTotal % 60)
        } else if (isSosActive) {
            binding.tvSmsTimer.visibility = View.VISIBLE
            binding.tvSmsTimer.text = "Triggering next alert..."
        } else {
            binding.tvSmsTimer.visibility = View.GONE
        }
    }

    private fun ensureSessionValid() {
        if (!SessionManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
            finish()
        }
    }

    private fun setupQuickActions() {
        binding.voiceAlertCard.setOnClickListener { binding.voiceAlertSwitch.isChecked = !binding.voiceAlertSwitch.isChecked }
        binding.voiceAlertSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveVoiceAlertState(isChecked)
            if (isChecked) requestAudioPermission() else stopVoiceService()
        }
        
        val alertPrefs = getSharedPreferences("alert_settings", MODE_PRIVATE)
        binding.switchSmsAlert.isChecked = alertPrefs.getBoolean("sms_alert_enabled", true)
        binding.switchAutoCall.isChecked = alertPrefs.getBoolean("auto_call_enabled", false)
        if (binding.switchAutoCall.isChecked) binding.callRecipientLayout.visibility = View.VISIBLE

        binding.switchSmsAlert.setOnCheckedChangeListener { _, isChecked ->
            alertPrefs.edit().putBoolean("sms_alert_enabled", isChecked).apply()
            if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 101)
            }
        }

        binding.switchAutoCall.setOnCheckedChangeListener { _, isChecked ->
            alertPrefs.edit().putBoolean("auto_call_enabled", isChecked).apply()
            binding.callRecipientLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 104)
            }
        }

        binding.spinnerCallRecipient.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in smsContacts.indices) {
                    alertPrefs.edit().putString("auto_call_recipient", smsContacts[position].phone).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.cardAISafetyHome.setOnClickListener { startActivity(Intent(this, AiChatActivity::class.java)) }
        binding.guardiansCard.setOnClickListener { startActivity(Intent(this, AddGuardianActivity::class.java)) }
        binding.historyCard.setOnClickListener { startActivity(Intent(this, AlertHistoryActivity::class.java)) }
        binding.myAlertsCard.setOnClickListener { startActivity(Intent(this, AlertHistoryActivity::class.java)) }
        binding.settingsCard.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnAlertQueue.setOnClickListener { showQueueDialog() }
        binding.logoutButton.setOnClickListener { showLogoutDialog() }
    }
    
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to reset your local profile?")
            .setPositiveButton("Yes") { _, _ -> logout() }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun setupBottomNavigation() {
        findViewById<LinearLayout>(R.id.navGuardians)?.setOnClickListener { 
            startActivity(Intent(this, AddGuardianActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }) 
        }
        findViewById<LinearLayout>(R.id.navHistory)?.setOnClickListener { 
            startActivity(Intent(this, AlertHistoryActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }) 
        }
        findViewById<MaterialCardView>(R.id.sosButton)?.setOnClickListener { 
            SOSHelper.showSOSConfirmation(this) 
        }
        findViewById<LinearLayout>(R.id.navGhistory)?.setOnClickListener { 
            startActivity(Intent(this, GuardianMapActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }) 
        }
        findViewById<LinearLayout>(R.id.navProfile)?.setOnClickListener { 
            startActivity(Intent(this, EditProfileActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }) 
        }
    }
    
    private fun saveVoiceAlertState(isEnabled: Boolean) { 
        getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit().putBoolean("voice_alert_enabled", isEnabled).apply() 
    }
    
    private fun restoreVoiceAlertState() {
        val isServiceRunning = isServiceRunning(VoskWakeWordService::class.java)
        val savedState = getSharedPreferences("vasatey_prefs", MODE_PRIVATE).getBoolean("voice_alert_enabled", false)
        binding.voiceAlertSwitch.setOnCheckedChangeListener(null)
        binding.voiceAlertSwitch.isChecked = isServiceRunning && savedState
        binding.voiceAlertSwitch.setOnCheckedChangeListener { _, checked ->
            saveVoiceAlertState(checked)
            if (checked) requestAudioPermission() else stopVoiceService()
        }
    }
    
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { serviceClass.name == it.service.className }
    }
    
    private fun stopVoiceService() {
        stopService(Intent(this, VoskWakeWordService::class.java))
        Toast.makeText(this, "Voice alert stopped", Toast.LENGTH_SHORT).show()
    }

    private fun loadUserProfile() {
        val wakeWord = getSharedPreferences("vasatey_prefs", MODE_PRIVATE).getString("wake_word", "help me") ?: "help me"
        binding.voiceStatusText.text = "Say '$wakeWord' to alert"
    }

    private fun requestAudioPermission() { 
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        } else {
            startListeningService()
        }
    }

    private fun startListeningService() {
        val intent = Intent(this, VoskWakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Voice listening service started", Toast.LENGTH_SHORT).show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean = true

    private fun logout() {
        SessionManager.clearSession()
        startActivity(Intent(this@HomeActivity, LoginActivity::class.java).apply { 
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK 
        })
        finish()
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.SEND_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECEIVE_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), ALL_PERMISSIONS_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListeningService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Emergency features enabled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateSignalMeterUi(info: NetworkMonitor.SignalInfo) {
        binding.tvSignalMeter.text = info.label
        val color = when {
            !info.isOnline || info.level == 0 -> Color.parseColor("#FF5252") // Red
            info.level == 1 -> Color.parseColor("#FF9800") // Orange
            info.level == 2 -> Color.parseColor("#FFD600") // Yellow
            info.level == 3 -> Color.parseColor("#76FF03") // Light Green
            else -> Color.parseColor("#00E676") // Bright Green
        }
        binding.dotSignalIndicator.backgroundTintList = ColorStateList.valueOf(color)

        // If offline or alerts queued, display informative banner
        val queueCount = AlertQueueManager.getQueueCount(this)
        if (queueCount > 0) {
            binding.layoutQueueNotice.visibility = View.VISIBLE
            binding.tvQueueNoticeText.text = "$queueCount Alert(s) Queued - Retrying on Signal"
        } else if (!info.isOnline || info.level == 0) {
            binding.layoutQueueNotice.visibility = View.VISIBLE
            binding.tvQueueNoticeText.text = "Offline / Low Signal: Alerts will queue locally"
        } else {
            binding.layoutQueueNotice.visibility = View.GONE
        }
    }

    private fun updateQueueBadgeUi() {
        val count = AlertQueueManager.getQueueCount(this)
        if (count > 0) {
            binding.tvQueueBadge.visibility = View.VISIBLE
            binding.tvQueueBadge.text = if (count > 9) "9+" else count.toString()
            binding.layoutQueueNotice.visibility = View.VISIBLE
            binding.tvQueueNoticeText.text = "$count Alert(s) Queued - Retrying on Signal"
        } else {
            binding.tvQueueBadge.visibility = View.GONE
            val currentLevel = NetworkMonitor.getCurrentSignalLevel(this)
            val isOnline = NetworkMonitor.isCellularOrNetworkAvailable(this)
            if (!isOnline || currentLevel == 0) {
                binding.layoutQueueNotice.visibility = View.VISIBLE
                binding.tvQueueNoticeText.text = "Offline / Low Signal: Alerts will queue locally"
            } else {
                binding.layoutQueueNotice.visibility = View.GONE
            }
        }
    }

    private fun showQueueDialog() {
        val alerts = AlertQueueManager.getQueuedAlerts(this)
        if (alerts.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Alert Queue (Offline Storage)")
                .setMessage("All emergency alerts have been dispatched successfully. There are no pending alerts in the offline queue.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val items = alerts.mapIndexed { idx, it ->
            val loc = if (it.latitude != null && it.longitude != null) {
                String.format("%.4f, %.4f", it.latitude, it.longitude)
            } else "GPS pending"
            val summary = if (!it.situationSummary.isNullOrBlank()) "\n   Summary: ${it.situationSummary.take(50)}" else ""
            "${idx + 1}. [${it.timestamp}]\n   Location: $loc$summary\n   Status: Pending Cellular Signal"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Pending Offline Alerts (${alerts.size})")
            .setItems(items, null)
            .setPositiveButton("Send Now (Flush)") { _, _ ->
                Toast.makeText(this, "Flushing alert queue now...", Toast.LENGTH_SHORT).show()
                AlertQueueManager.flushQueue(this)
            }
            .setNegativeButton("Clear Queue") { _, _ ->
                lifecycleScope.launch {
                    AlertQueueManager.clearQueue(this@HomeActivity)
                    updateQueueBadgeUi()
                    Toast.makeText(this@HomeActivity, "Queue cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Close", null)
            .show()
    }
}
