package com.sriox.vasateysec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sriox.vasateysec.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PERMISSIONS_REQUEST_CODE = 1
    private val BACKGROUND_LOCATION_REQUEST_CODE = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener { startListening() }
        binding.stopButton.setOnClickListener { stopListening() }

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Add location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            // Show explanation before requesting permissions
            showInitialPermissionDialog(permissionsToRequest)
        } else {
            // If foreground location is already granted, request background location
            requestBackgroundLocationIfNeeded()
        }
    }
    
    private fun showInitialPermissionDialog(permissionsToRequest: List<String>) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📱 App Permissions - Step 1 of 2")
            .setMessage("This app needs several permissions:\n\n" +
                    "📷 Camera - Take emergency photos\n" +
                    "🎤 Microphone - Detect voice commands\n" +
                    "📍 Location - Share your location\n" +
                    "🔔 Notifications - Receive alerts\n\n" +
                    "⚠️ IMPORTANT:\n" +
                    "In the next screens, select \"Allow\" or \"Allow while using the app\" for all permissions.\n\n" +
                    "After this, you'll get ONE MORE dialog asking for \"Allow all the time\" - that's the important one!")
            .setPositiveButton("Continue") { _, _ ->
                ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            }
            .setNegativeButton("Exit App") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun startListening() {
        if (hasPermissions()) {
            val serviceIntent = Intent(this, VoskWakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            binding.resultText.text = "Listening in background..."
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        } else {
            checkPermissions()
        }
    }

    private fun stopListening() {
        val serviceIntent = Intent(this, VoskWakeWordService::class.java)
        stopService(serviceIntent)
        binding.resultText.text = "Press Start to Listen"
        binding.startButton.visibility = View.VISIBLE
        binding.stopButton.visibility = View.GONE
    }

    private fun hasPermissions(): Boolean {
        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        return audioPermission && notificationPermission && (fineLocation || coarseLocation)
    }

    private fun requestBackgroundLocationIfNeeded() {
        // Only request background location on Android 10 (Q) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Show explanation dialog that user must accept
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("📍 Location Permission - Final Step")
                    .setMessage("⚠️ IMPORTANT: In the next screen, you will see 3 options:\n\n" +
                            "1️⃣ Allow all the time ✅ (SELECT THIS)\n" +
                            "2️⃣ Allow only while using the app ❌\n" +
                            "3️⃣ Don't allow ❌\n\n" +
                            "You MUST select \"Allow all the time\" so:\n\n" +
                            "✓ Guardians can request your location 24/7\n" +
                            "✓ Emergency alerts work even when app is closed\n" +
                            "✓ You're protected at all times\n\n" +
                            "⚠️ Without \"Allow all the time\", the app cannot protect you.")
                    .setPositiveButton("Continue") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            BACKGROUND_LOCATION_REQUEST_CODE
                        )
                    }
                    .setNegativeButton("Exit App") { _, _ ->
                        Toast.makeText(this, "App cannot function without this permission", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            // Check if all foreground permissions are granted
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // After foreground permissions are granted, request background location
                requestBackgroundLocationIfNeeded()
            } else {
                // If user denied, ask again
                showPermissionDeniedDialog()
            }
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST_CODE) {
            // Check if background location was granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // Success! Background location granted
                    Toast.makeText(this, "✅ All permissions granted! App is ready.", Toast.LENGTH_LONG).show()
                } else {
                    // User selected "Allow only while using" or "Deny"
                    showBackgroundLocationDeniedDialog()
                }
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Permissions Required")
            .setMessage("This app needs these permissions to function:\n\n" +
                    "📷 Camera - Take emergency photos\n" +
                    "🎤 Microphone - Detect voice commands\n" +
                    "📍 Location - Share your location\n" +
                    "🔔 Notifications - Receive alerts\n\n" +
                    "⚠️ Please select \"Allow\" for all permissions in the next screen.")
            .setPositiveButton("Grant Permissions") { _, _ ->
                checkPermissions()
            }
            .setNegativeButton("Exit App") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showBackgroundLocationDeniedDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ \"Allow all the time\" Required")
            .setMessage("You selected \"Allow only while using the app\" or \"Deny\".\n\n" +
                    "⚠️ This app REQUIRES \"Allow all the time\" to:\n\n" +
                    "• Receive location requests from guardians even when app is closed\n" +
                    "• Respond to emergencies 24/7\n" +
                    "• Protect you at all times\n\n" +
                    "Please select \"Allow all the time\" in the next screen.")
            .setPositiveButton("Grant Permission") { _, _ ->
                requestBackgroundLocationIfNeeded()
            }
            .setNegativeButton("Exit App") { _, _ ->
                Toast.makeText(this, "App cannot function without \"Allow all the time\" permission", Toast.LENGTH_LONG).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }
}
