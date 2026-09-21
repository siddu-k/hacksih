package com.sriox.vasateysec.utils

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sriox.vasateysec.models.SmsContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmsHelper {
    private const val TAG = "SmsHelper"
    private const val PREFS_NAME = "trusted_contacts_storage"
    private const val STORAGE_KEY = "permanent_sms_contacts"
    private const val ACTION_SMS_SENT = "com.sriox.vasateysec.SMS_SENT"
    private const val KEY_LAST_SENT_TIME = "last_sms_sent_timestamp"
    
    /**
     * Gets the remaining time in milliseconds until the next SMS can be sent for hardware triggers.
     * Uses persistent storage to ensure accuracy even after app restarts.
     */
    fun getRemainingCooldownMs(context: Context): Long {
        val settingsPrefs = context.getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)
        val cooldownMinutes = settingsPrefs.getInt("hardware_sms_interval", 2)
        val smsCooldownMs = cooldownMinutes * 60 * 1000L
        
        val lastSentTime = settingsPrefs.getLong(KEY_LAST_SENT_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastSentTime
        
        return if (lastSentTime > 0 && timeDiff < smsCooldownMs) {
            smsCooldownMs - timeDiff
        } else {
            0L
        }
    }

    /**
     * Sends emergency SMS and triggers auto-call.
     * Uses persistent timestamps to fix the 2-minute timer bug.
     * If cellular network is unavailable or signal is zero, enqueues the alert to AlertQueueManager.
     */
    suspend fun sendEmergencySms(
        context: Context, 
        latitude: Double?, 
        longitude: Double?, 
        isHardware: Boolean = false,
        situationSummary: String? = null,
        isQueuedRetry: Boolean = false
    ) {
        val alertPrefs = context.getSharedPreferences("alert_settings", Context.MODE_PRIVATE)
        val settingsPrefs = context.getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)
        
        // SMS: Always ON for hardware triggers; defaults to true for safety
        val smsEnabled = if (isHardware) true else alertPrefs.getBoolean("sms_alert_enabled", true)
        
        // Auto-Call: Now strictly respects the "Hardware Auto Call" toggle for ESP32.
        val autoCallEnabled = if (isHardware) {
            settingsPrefs.getBoolean("hardware_auto_call_enabled", false)
        } else {
            alertPrefs.getBoolean("auto_call_enabled", false)
        }

        val cooldownMinutes = settingsPrefs.getInt("hardware_sms_interval", 2)
        val smsCooldownMs = cooldownMinutes * 60 * 1000L
        
        val lastSentTime = settingsPrefs.getLong(KEY_LAST_SENT_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastSentTime
        
        Log.d(TAG, "🏁 SOS_TRIGGER -> Hardware: $isHardware, SMS: $smsEnabled, Call: $autoCallEnabled, isQueuedRetry: $isQueuedRetry")

        // 5-second universal cooldown limit to prevent multiple SMS at once (bypassed for queued flushes)
        val minCooldownMs = 5000L
        if (!isQueuedRetry && lastSentTime > 0 && timeDiff < minCooldownMs) {
            val remainingSec = ((minCooldownMs - timeDiff) / 1000) + 1
            Log.w(TAG, "🚫 Universal 5s Cooldown Active: please wait ${remainingSec}s before sending another SMS alert.")
            return
        }

        // Apply persistent cooldown for hardware (e.g. 2 min)
        if (!isQueuedRetry && isHardware && lastSentTime > 0 && timeDiff < smsCooldownMs) {
            val remaining = (smsCooldownMs - timeDiff) / 1000
            Log.d(TAG, "🚫 Persistent Cooldown Active: $remaining seconds left.")
            return
        }

        // Offline / Low Network Check: If no cellular signal or network is unavailable, enqueue for auto-dispatch
        val isOffline = !NetworkMonitor.isCellularOrNetworkAvailable(context) || NetworkMonitor.getCurrentSignalLevel(context) == 0
        if (!isQueuedRetry && isOffline) {
            Log.w(TAG, "⚠️ Offline / No cellular signal detected! Enqueuing alert to AlertQueueManager...")
            AlertQueueManager.enqueueAlert(
                context = context,
                latitude = latitude,
                longitude = longitude,
                situationSummary = situationSummary,
                isHardware = isHardware
            )
        }

        withContext(Dispatchers.IO) {
            try {
                val contacts = getFromLocalStorage(context)
                if (contacts.isEmpty()) {
                    Log.e(TAG, "❌ ABORT: No contacts found in local storage.")
                    return@withContext
                }

                // 1. SMS Sequence (phase-1, immediate — summary added if already ready)
                if (smsEnabled) {
                    sendSmsSequence(context, contacts, latitude, longitude, situationSummary)
                    // PERSIST the sent time immediately
                    settingsPrefs.edit().putLong(KEY_LAST_SENT_TIME, System.currentTimeMillis()).apply()
                    Log.d(TAG, "✅ SMS Sent and Timestamp Persisted")
                }

                // 2. Auto-Call Sequence
                if (autoCallEnabled) {
                    val selectedPhone = alertPrefs.getString("auto_call_recipient", null)
                    var callTarget = selectedPhone ?: contacts.firstOrNull()?.phone
                    
                    if (callTarget != null) {
                        Log.d(TAG, "📞 Triggering Auto-Call to: $callTarget")
                        withContext(Dispatchers.Main) { 
                            triggerAutoCall(context, callTarget) 
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "🆘 Emergency Error: ${e.message}")
                if (!isQueuedRetry) {
                    AlertQueueManager.enqueueAlert(
                        context = context,
                        latitude = latitude,
                        longitude = longitude,
                        situationSummary = situationSummary,
                        isHardware = isHardware
                    )
                }
            }
        }
    }

    /**
     * Phase-2: send 10s summary as follow-up SMS to same contacts.
     * Called after wake-word + 10s STT + local summarize. Best-effort.
     */
    suspend fun sendSummarySms(
        context: Context,
        latitude: Double?,
        longitude: Double?,
        summaryText: String
    ) = withContext(Dispatchers.IO) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return@withContext
            val contacts = getFromLocalStorage(context)
            if (contacts.isEmpty()) {
                Log.w(TAG, "summary SMS: no contacts in local storage")
                return@withContext
            }
            // Reuse sequence builder with summary-only message (no cooldown — it's part of same SOS)
            sendSmsSequence(context, contacts, latitude, longitude, summaryText, isFollowUp = true)
        } catch (e: Exception) {
            Log.e(TAG, "summary SMS failed: ${e.message}", e)
        }
    }

    private fun sendSmsSequence(context: Context, contacts: List<SmsContact>, latitude: Double?, longitude: Double?, situationSummary: String? = null, isFollowUp: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return

        val userName = SessionManager.getUserName() ?: "User"
        val userPhone = SessionManager.getUserPhone()?.takeIf { it.isNotBlank() }
        val locationUrl = if (latitude != null && longitude != null) "https://maps.google.com/?q=$latitude,$longitude" else "Location unknown"
        val timeStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val batteryInfo = if (batteryLevel in 0..100) " | Bat: $batteryLevel%" else ""
        val phoneInfo = if (!userPhone.isNullOrBlank()) " | Ph: $userPhone" else ""

        val cleanSummary = situationSummary?.trim()?.take(180)
        val tag = EmergencySmsReceiver.SMS_EMERGENCY_TAG

        val message = if (isFollowUp) {
            if (cleanSummary.isNullOrBlank()) "$tag SOS UPDATE from $userName\nTime: $timeStr\n$locationUrl"
            else "$tag SOS UPDATE from $userName\nTime: $timeStr\nSummary: $cleanSummary\n$locationUrl"
        } else {
            val header = "$tag SOS ALERT! $userName needs help!\nTime: $timeStr$batteryInfo$phoneInfo\nLocation: $locationUrl"
            if (cleanSummary.isNullOrBlank()) header
            else "$header\nSummary: $cleanSummary"
        }

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        for (contact in contacts) {
            var phone = contact.phone.trim()
            if (phone.length == 10 && !phone.startsWith("+")) phone = "+91$phone"

            val sentIntent = Intent(ACTION_SMS_SENT).apply { 
                putExtra("phone", phone)
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, phone.hashCode(), sentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            try {
                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    val sentIntents = ArrayList<PendingIntent>()
                    for (i in parts.indices) sentIntents.add(pendingIntent)
                    smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
                } else {
                    smsManager.sendTextMessage(phone, null, message, pendingIntent, null)
                }
            } catch (e: Exception) { }
        }
    }

    private fun triggerAutoCall(context: Context, phoneNumber: String) {
        var phone = phoneNumber.trim()
        if (phone.length == 10 && !phone.startsWith("+")) phone = "+91$phone"

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(callIntent)
            } catch (e: Exception) { }
        }
    }

    /**
     * Sends a cancellation SMS to all trusted contacts confirming user is safe.
     * No password required.
     */
    suspend fun sendCancelAlertSms(context: Context) = withContext(Dispatchers.IO) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return@withContext
            val contacts = getFromLocalStorage(context)
            if (contacts.isEmpty()) return@withContext

            val userName = SessionManager.getUserName() ?: "User"
            val timeStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
            val tag = EmergencySmsReceiver.SMS_EMERGENCY_TAG
            val message = "$tag CANCEL ALERT: $userName is SAFE.\nSituation resolved / False alarm.\nTime: $timeStr"

            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            for (contact in contacts) {
                var phone = contact.phone.trim()
                if (phone.length == 10 && !phone.startsWith("+")) phone = "+91$phone"
                try {
                    smsManager.sendTextMessage(phone, null, message, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send cancel SMS to $phone: ${e.message}")
                }
            }
            Log.d(TAG, "✅ Cancel Alert SMS dispatched to ${contacts.size} contacts")
        } catch (e: Exception) {
            Log.e(TAG, "sendCancelAlertSms error: ${e.message}", e)
        }
    }

    fun saveToLocalStorage(context: Context, contacts: List<SmsContact>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(STORAGE_KEY, Json.encodeToString(contacts)).apply()
    }

    fun getFromLocalStorage(context: Context): List<SmsContact> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(STORAGE_KEY, null)
        return if (json != null) try { Json.decodeFromString(json) } catch (e: Exception) { emptyList() } else emptyList()
    }
}
