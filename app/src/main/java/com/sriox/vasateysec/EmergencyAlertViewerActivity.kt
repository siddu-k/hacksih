package com.sriox.vasateysec

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.sriox.vasateysec.databinding.ActivityHelpRequestBinding
import com.sriox.vasateysec.utils.AlarmSoundPlayer
import com.sriox.vasateysec.utils.EmergencySmsReceiver
import com.sriox.vasateysec.utils.SessionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EmergencyAlertViewerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityHelpRequestBinding
    private var googleMap: GoogleMap? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var phoneNumber: String? = null
    private var alertId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Auto-wake screen & keep showing over lockscreen on guardian device (no auto-dismiss)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityHelpRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alertId = intent.getStringExtra("alertId") ?: savedInstanceState?.getString("alertId")
        setupStandardAlertUI()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupStandardAlertUI()
    }
    
    private fun setupStandardAlertUI() {
        val fullName = intent.getStringExtra("fullName") ?: "Unknown"
        val email = intent.getStringExtra("email") ?: ""
        val isSmsAlert = intent.getBooleanExtra("isSmsAlert", false)
        val situationSummary = intent.getStringExtra("situationSummary")
        phoneNumber = intent.getStringExtra("phoneNumber") ?: ""
        
        val latStr = intent.getStringExtra("latitude") ?: ""
        val lonStr = intent.getStringExtra("longitude") ?: ""
        latitude = latStr.toDoubleOrNull()
        longitude = lonStr.toDoubleOrNull()
        
        val timestamp = intent.getStringExtra("timestamp") ?: ""

        val isCancelled = intent.getBooleanExtra("isCancelled", false) || 
            situationSummary?.contains("CANCEL ALERT", ignoreCase = true) == true ||
            situationSummary?.contains("is SAFE", ignoreCase = true) == true

        if (isCancelled) {
            binding.headerTitle.text = "USER IS SAFE - CANCELLED"
            binding.headerTitle.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            AlarmSoundPlayer.stopAlarm(this)
        } else if (isSmsAlert) {
            binding.headerTitle.text = "EMERGENCY SMS ALERT"
        }

        binding.userName.text = fullName
        binding.userEmail.text = if (!situationSummary.isNullOrBlank()) situationSummary else email
        binding.userPhone.text = phoneNumber
        binding.alertTime.text = formatTimestamp(timestamp)

        setupCommonFeatures(isCancelled)
        loadPhotos()
    }

    private fun setupCommonFeatures(isCancelled: Boolean = false) {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        binding.callButton.setOnClickListener { makeCall(phoneNumber ?: "") }
        binding.navigateButton.setOnClickListener { navigateTo() }
        
        binding.confirmAlertButton.visibility = View.VISIBLE
        if (isCancelled) {
            binding.confirmAlertButton.text = "ALERT RESOLVED (USER IS SAFE)"
            binding.confirmAlertButton.isEnabled = false
            binding.confirmAlertButton.setBackgroundColor(android.graphics.Color.parseColor("#388E3C"))
        } else {
            binding.confirmAlertButton.text = "CONFIRM & SEND REPLY (SMS)"
            binding.confirmAlertButton.setOnClickListener {
                AlarmSoundPlayer.stopAlarm(this)
                val targetPhone = phoneNumber?.trim()
                if (!targetPhone.isNullOrBlank()) {
                    sendGuardianConfirmationSms(targetPhone)
                    binding.confirmAlertButton.text = "CONFIRMED (SMS SENT TO USER)"
                    binding.confirmAlertButton.isEnabled = false
                    android.widget.Toast.makeText(this, "Confirmation sent to user via SMS", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    binding.confirmAlertButton.text = "ALERT ACKNOWLEDGED"
                    binding.confirmAlertButton.isEnabled = false
                }
            }
        }

        binding.headerIcon.setOnClickListener {
            AlarmSoundPlayer.stopAlarm(this)
            finish()
        }
    }

    private fun sendGuardianConfirmationSms(targetPhone: String) {
        try {
            var phone = targetPhone
            if (phone.length == 10 && !phone.startsWith("+")) phone = "+91$phone"
            val guardianName = SessionManager.getUserName() ?: "Guardian"
            val timeStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
            val tag = EmergencySmsReceiver.SMS_EMERGENCY_TAG
            val message = "$tag GUARDIAN ACKNOWLEDGED: Help is on the way! $guardianName received your alert.\nTime: $timeStr"
            
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phone, null, message, null, null)
        } catch (e: Exception) {
            android.util.Log.e("AlertViewer", "Failed to send confirmation SMS: ${e.message}")
        }
    }

    private fun makeCall(phone: String) {
        if (phone.isNotEmpty()) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        }
    }

    private fun navigateTo() {
        latitude?.let { lat ->
            longitude?.let { lon ->
                val uri = Uri.parse("google.navigation:q=$lat,$lon")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
                startActivity(intent)
            }
        }
    }

    private fun formatTimestamp(ts: String): String {
        if (ts.isBlank()) return "Just now"
        if (ts.contains("AM", ignoreCase = true) || ts.contains("PM", ignoreCase = true)) {
            return ts
        }
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(ts)
            SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(date!!)
        } catch (e: Exception) {
            try {
                val millis = ts.toLongOrNull()
                if (millis != null) {
                    SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(millis))
                } else {
                    ts
                }
            } catch (_: Exception) { ts }
        }
    }

    private fun loadPhotos() {
        val front = intent.getStringExtra("frontPhotoUrl")
        val back = intent.getStringExtra("backPhotoUrl")
        if (!front.isNullOrEmpty() || !back.isNullOrEmpty()) {
            binding.photosContainer.visibility = View.VISIBLE
            binding.photosHeader.visibility = View.VISIBLE
            if (!front.isNullOrEmpty()) {
                binding.frontPhotoCard.visibility = View.VISIBLE
                if (front.startsWith("/")) {
                    Glide.with(this).load(File(front)).into(binding.frontPhoto)
                } else {
                    Glide.with(this).load(front).into(binding.frontPhoto)
                }
            }
            if (!back.isNullOrEmpty()) {
                binding.backPhotoCard.visibility = View.VISIBLE
                if (back.startsWith("/")) {
                    Glide.with(this).load(File(back)).into(binding.backPhoto)
                } else {
                    Glide.with(this).load(back).into(binding.backPhoto)
                }
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE
        latitude?.let { lat ->
            longitude?.let { lon ->
                val pos = LatLng(lat, lon)
                googleMap?.addMarker(MarkerOptions().position(pos).title("Emergency Location"))
                googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("alertId", alertId)
    }
}
