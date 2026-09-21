package com.sriox.vasateysec.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.sriox.vasateysec.VoskWakeWordService
import com.sriox.vasateysec.services.BleGuardianService

/**
 * Auto-starts VoskWakeWordService and BleGuardianService on device boot
 * if enabled in local settings.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d(TAG, "Device reboot completed ($action). Checking auto-start services...")

            val prefs = context.getSharedPreferences("vasatey_prefs", Context.MODE_PRIVATE)
            val settingsPrefs = context.getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)

            // 1. Auto-start Voice Wake-word Service if enabled
            val isVoiceAlertEnabled = prefs.getBoolean("voice_alert_enabled", false)
            val hasAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            if (isVoiceAlertEnabled && hasAudioPermission) {
                Log.d(TAG, "Auto-starting VoskWakeWordService...")
                try {
                    val serviceIntent = Intent(context, VoskWakeWordService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start VoskWakeWordService on boot: ${e.message}", e)
                }
            }

            // 2. Auto-start BLE Guardian Service if device address is saved
            val savedBleAddress = settingsPrefs.getString("last_device_address", null)
            if (savedBleAddress != null) {
                Log.d(TAG, "Auto-starting BleGuardianService for saved watch: $savedBleAddress")
                try {
                    val bleIntent = Intent(context, BleGuardianService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, bleIntent)
                    } else {
                        context.startService(bleIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start BleGuardianService on boot: ${e.message}", e)
                }
            }
        }
    }
}
