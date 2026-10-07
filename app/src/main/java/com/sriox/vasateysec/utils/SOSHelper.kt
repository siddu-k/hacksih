package com.sriox.vasateysec.utils

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

object SOSHelper {
    
    fun showSOSConfirmation(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Emergency SOS Alert")
            .setMessage("Are you sure you want to send an emergency alert to all your guardians?\n\nThis will:\n• Send your location\n• Capture photos\n• Notify all guardians immediately")
            .setPositiveButton("Send Alert") { dialog, _ ->
                dialog.dismiss()
                triggerEmergencyAlert(activity)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }
    
    private var lastManualAlertTime = 0L

    private fun triggerEmergencyAlert(activity: Activity) {
        val now = System.currentTimeMillis()
        if (now - lastManualAlertTime < 5000L) {
            val waitSec = ((5000L - (now - lastManualAlertTime)) / 1000) + 1
            Toast.makeText(activity, "Alert already sent. Please wait ${waitSec}s.", Toast.LENGTH_SHORT).show()
            return
        }
        lastManualAlertTime = now

        if (activity !is LifecycleOwner) {
            Toast.makeText(activity, "Unable to trigger alert", Toast.LENGTH_SHORT).show()
            return
        }

        // Spoken confirmation on this phone.
        com.sriox.vasateysec.utils.VoiceFeedback.speakManualSos(activity)

        (activity as LifecycleOwner).lifecycleScope.launch {
            try {
                // Permissions check first so location/photos don't silently fail.
                val missing = mutableListOf<String>()
                if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
                    missing.add("SMS")
                if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    missing.add("Camera")
                if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    missing.add("Location")
                if (missing.isNotEmpty()) {
                    val toReq = missing.map {
                        when (it) {
                            "SMS" -> Manifest.permission.SEND_SMS
                            "Camera" -> Manifest.permission.CAMERA
                            else -> Manifest.permission.ACCESS_FINE_LOCATION
                        }
                    }.toTypedArray()
                    ActivityCompat.requestPermissions(activity, toReq, 126)
                    Toast.makeText(activity, "Grant: ${missing.joinToString(", ")} — then tap SOS again", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Fail fast: no contacts
                val contacts = com.sriox.vasateysec.utils.SmsHelper.getFromLocalStorage(activity)
                if (contacts.isEmpty()) {
                    Toast.makeText(activity, "Add an emergency contact first (Guardians tab)", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (contacts.none { com.sriox.vasateysec.utils.SmsHelper.isValidPhone(it.phone) }) {
                    Toast.makeText(activity, "No valid phone numbers. Fix guardian contact numbers.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Use the SAME location engine as voice path (5-strategy fallback).
                Toast.makeText(activity, "Getting location…", Toast.LENGTH_SHORT).show()
                // (location fetched below)

                // Manual SOS now uses the SAME 5-strategy location engine:
                // last-known → fresh GPS/Network → system → passive → stale.
                // This replaces the old GPS-only lastKnown which returned null indoors.
                val location = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.sriox.vasateysec.utils.LocationManager.getCurrentLocation(activity)
                }
                val latitude = location?.latitude
                val longitude = location?.longitude
                val accuracy = location?.accuracy

                android.util.Log.d("SOSHelper", "Manual SOS: lat=$latitude, lon=$longitude, accuracy=$accuracy")
                if (location == null) {
                    Toast.makeText(activity, "No GPS fix yet — move near a window / outdoors and tap SOS again", Toast.LENGTH_LONG).show()
                }
                
                // Show progress
                Toast.makeText(activity, "Capturing photos and sending alert...", Toast.LENGTH_SHORT).show()
                val photos = CameraManager.captureEmergencyPhotos(activity)
                
                // Send emergency alert using AlertManager
                val result = AlertManager.sendEmergencyAlert(
                    context = activity,
                    latitude = latitude,
                    longitude = longitude,
                    locationAccuracy = accuracy,
                    frontPhotoFile = photos.frontPhoto,
                    backPhotoFile = photos.backPhoto
                )
                
                if (result.isSuccess) {
                    Toast.makeText(
                        activity,
                        "Emergency alert sent to guardians!",
                        Toast.LENGTH_LONG
                    ).show()
                    val triggerIntent = Intent("com.sriox.vasateysec.ALERT_TRIGGERED")
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(activity).sendBroadcast(triggerIntent)
                    activity.sendBroadcast(triggerIntent)
                } else {
                    Toast.makeText(
                        activity,
                        "Failed to send alert: ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("SOSHelper", "Manual SOS failed", e)
                Toast.makeText(
                    activity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
