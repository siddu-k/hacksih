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

    /** Result of an SMS dispatch — lets callers show the real outcome instead of "sent". */
    sealed interface SmsResult {
        data class Sent(val sentCount: Int, val total: Int) : SmsResult
        data class Failed(val reason: String) : SmsResult
    }

    /** A phone is usable only if it has at least 7 digits (rejects emails / junk). */
    fun isValidPhone(phone: String): Boolean {
        val digits = phone.filter { it.isDigit() }
        return digits.length >= 7
    }

    fun normalizePhone(phone: String): String {
        var p = phone.trim().replace(Regex("[\\s\\-()]+"), "")
        if (p.length == 10 && !p.startsWith("+")) p = "+91$p"
        return p
    }
    
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
        isQueuedRetry: Boolean = false,
        photoLinks: String? = null
    ): SmsResult {
        val alertPrefs = context.getSharedPreferences("alert_settings", Context.MODE_PRIVATE)
        val settingsPrefs = context.getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)
        
        // SMS: Always ON for hardware triggers; defaults to true for safety
        val smsEnabled = if (isHardware) true else alertPrefs.getBoolean("sms_alert_enabled", true)
        if (!smsEnabled) {
            Log.w(TAG, "❌ ABORT: SMS alerts are switched OFF in settings.")
            return SmsResult.Failed("SMS alerts are switched OFF. Enable SMS Alert in Home.")
        }

        // Fail fast with a clear reason instead of silently doing nothing.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ ABORT: SEND_SMS permission not granted.")
            return SmsResult.Failed("SMS permission not granted. Allow SMS permission and retry.")
        }

        val contacts = getFromLocalStorage(context)
        if (contacts.isEmpty()) {
            Log.e(TAG, "❌ ABORT: No contacts found in local storage.")
            return SmsResult.Failed("No emergency contacts. Add a guardian contact first.")
        }
        val validContacts = contacts.filter { isValidPhone(it.phone) }
        if (validContacts.isEmpty()) {
            Log.e(TAG, "❌ ABORT: contacts exist but none has a valid phone number.")
            return SmsResult.Failed("No valid phone numbers. Fix guardian contact numbers.")
        }
        
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
            return SmsResult.Failed("Alert already sent. Please wait ${remainingSec}s.")
        }

        // Apply persistent cooldown for hardware (e.g. 2 min)
        if (!isQueuedRetry && isHardware && lastSentTime > 0 && timeDiff < smsCooldownMs) {
            val remaining = (smsCooldownMs - timeDiff) / 1000
            Log.d(TAG, "🚫 Persistent Cooldown Active: $remaining seconds left.")
            return SmsResult.Failed("Hardware cooldown: next alert in ${remaining}s.")
        }

        // Opportunistic flush: if we're online and older SOS are still queued
        // (e.g. queued while the app was closed), send them first. Works from
        // any entry point — voice, button, or hardware — no screen needed.
        if (!isQueuedRetry) {
            try {
                val pending = AlertQueueManager.getQueueCount(context)
                if (pending > 0 && NetworkMonitor.isCellularOrNetworkAvailable(context)) {
                    Log.d(TAG, "Flushing $pending stale queued alert(s) before current SOS...")
                    AlertQueueManager.flushQueue(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pre-flush check failed (non-fatal): ${e.message}")
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. SMS Sequence — only mark timestamp on real delivery to SmsManager.
                // NOTE: no pre-enqueue here. We queue only when the send below
                // actually fails — pre-enqueueing caused duplicate SMS on flaky signal.
                val sentCount = sendSmsSequence(context, validContacts, latitude, longitude, situationSummary, photoLinks = photoLinks, isHardware = isHardware)
                if (sentCount > 0) {
                    // PERSIST the sent time only on actual success so failures don't block retries
                    settingsPrefs.edit().putLong(KEY_LAST_SENT_TIME, System.currentTimeMillis()).apply()
                    Log.d(TAG, "✅ SMS dispatched to $sentCount/${validContacts.size} contacts. Timestamp persisted.")
                } else {
                    Log.e(TAG, "❌ SMS failed for all ${validContacts.size} contacts — timestamp NOT updated.")
                    if (!isQueuedRetry) {
                        AlertQueueManager.enqueueAlert(
                            context = context,
                            latitude = latitude,
                            longitude = longitude,
                            situationSummary = situationSummary,
                            isHardware = isHardware
                        )
                    }
                    return@withContext SmsResult.Failed("SMS failed to send. Queued for retry.")
                }

                // 2. Auto-Call Sequence
                if (autoCallEnabled) {
                    val selectedPhone = alertPrefs.getString("auto_call_recipient", null)
                    var callTarget = selectedPhone ?: validContacts.firstOrNull()?.phone
                    
                    if (callTarget != null) {
                        Log.d(TAG, "📞 Triggering Auto-Call to: $callTarget")
                        withContext(Dispatchers.Main) { 
                            triggerAutoCall(context, callTarget) 
                        }
                    }
                }

                SmsResult.Sent(sentCount, validContacts.size)
            } catch (e: Exception) {
                Log.e(TAG, "🆘 Emergency Error: ${e.message}", e)
                if (!isQueuedRetry) {
                    AlertQueueManager.enqueueAlert(
                        context = context,
                        latitude = latitude,
                        longitude = longitude,
                        situationSummary = situationSummary,
                        isHardware = isHardware
                    )
                }
                SmsResult.Failed("Send error: ${e.message ?: "unknown"}. Queued for retry.")
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

    private fun sendSmsSequence(context: Context, contacts: List<SmsContact>, latitude: Double?, longitude: Double?, situationSummary: String? = null, isFollowUp: Boolean = false, photoLinks: String? = null, isHardware: Boolean = false): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "sendSmsSequence aborted: SEND_SMS not granted")
            return 0
        }

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
            // Keep coordinates at the START so they survive multipart SMS splits:
            // part 1 always has LOC: + MAP link even if the tail is cut off.
            // Supabase photo links follow right after (PIC1:/PIC2:) when uploaded.
            val locToken = if (latitude != null && longitude != null) "LOC:$latitude,$longitude" else "LOC:unknown"
            val pics = if (photoLinks.isNullOrBlank()) "" else photoLinks.trim()
            val header = "$tag SOS ALERT! $userName needs help!\n$locToken\nMAP:$locationUrl" +
                (if (pics.isNotEmpty()) "\n$pics" else "") +
                "\nTime: $timeStr$batteryInfo$phoneInfo"
            if (cleanSummary.isNullOrBlank()) header
            else "$header\nSummary: $cleanSummary"
        }

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        var sent = 0
        for (contact in contacts) {
            val raw = contact.phone.trim()
            if (!isValidPhone(raw)) {
                Log.w(TAG, "Skipping invalid number '${contact.name}': $raw")
                continue
            }
            var phone = normalizePhone(raw)

            val sentIntent = Intent(ACTION_SMS_SENT).apply { 
                putExtra("phone", phone)
                // Location payload so SmsSentReceiver can re-queue this exact
                // alert if the radio reports NO_SERVICE / RADIO_OFF async.
                if (latitude != null) putExtra("latitude", latitude)
                if (longitude != null) putExtra("longitude", longitude)
                if (!cleanSummary.isNullOrBlank()) putExtra("situationSummary", cleanSummary)
                putExtra("isHardware", isHardware)
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, phone.hashCode(), sentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            try {
                val parts = smsManager.divideMessage(message)
                if (parts == null || parts.isEmpty()) {
                    Log.e(TAG, "divideMessage returned empty for $phone")
                    continue
                }
                if (parts.size > 1) {
                    val sentIntents = ArrayList<PendingIntent>()
                    for (i in parts.indices) sentIntents.add(pendingIntent)
                    smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
                } else {
                    smsManager.sendTextMessage(phone, null, message, pendingIntent, null)
                }
                sent++
            } catch (e: Exception) {
                Log.e(TAG, "SMS send failed to $phone: ${e.message}", e)
            }
        }
        return sent
    }

    private fun triggerAutoCall(context: Context, phoneNumber: String) {
        if (!isValidPhone(phoneNumber)) {
            Log.w(TAG, "Auto-call skipped: invalid number $phoneNumber")
            return
        }
        var phone = normalizePhone(phoneNumber)

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
                val raw = contact.phone.trim()
                if (!isValidPhone(raw)) continue
                val phone = normalizePhone(raw)
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
