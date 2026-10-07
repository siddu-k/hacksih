package com.sriox.vasateysec.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.sriox.vasateysec.EmergencyAlertViewerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Listens for incoming SahAi Emergency SMS messages.
 * Parses GPS coordinates, situation summaries, and sender details,
 * triggers the piercing emergency siren with DND bypass, and auto-opens the full alert viewer dashboard.
 */
class EmergencySmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EmergencySmsReceiver"
        const val SMS_EMERGENCY_TAG = "[SahAi SOS Alert]"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isEmpty()) return
            // Multipart SMS arrives as several PDUs: join ALL parts first.
            // Old code parsed each part alone, so part 1 had the tag but no
            // coordinates and part 2 had the map link but no tag → empty lat/lon.
            val sender = messages.firstOrNull()?.originatingAddress ?: "Unknown Guardian"
            val fullBody = messages.mapNotNull { it.messageBody }.joinToString("")
            if (fullBody.isBlank()) return
            handleEmergencyBody(context, fullBody, sender, messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis())
        }
    }

    private fun handleEmergencyBody(context: Context, body: String, sender: String, smsTimeMillis: Long) {
        Log.d(TAG, "SMS Received from $sender: ${body.take(80)}...")

        // Check if this is an official SahAi emergency SMS
        if (body.contains(SMS_EMERGENCY_TAG, ignoreCase = true) || body.contains("SahAi SOS", ignoreCase = true)) {
            Log.d(TAG, "🚨 MATCHED EMERGENCY SMS! Parsing coordinates and details...")

            val cleanBody = body.replace(SMS_EMERGENCY_TAG, "").trim()

            // 1. Extract GPS coordinates — tolerant to multipart splits / carrier rewrites.
            // New sender format puts "LOC:lat,lon" first; old format has maps.google.com/?q=lat,lon.
            val latitude: String
            val longitude: String
            val locToken = Regex("""LOC:\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(cleanBody)
            val mapLink = Regex("""[?&]q=\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(cleanBody)
            val barePair = Regex("""(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""").find(cleanBody)
            val coordMatch = locToken ?: mapLink ?: barePair
            latitude = coordMatch?.groupValues?.get(1) ?: ""
            longitude = coordMatch?.groupValues?.get(2) ?: ""
            if (latitude.isBlank()) Log.w(TAG, "No coordinates found in SMS body")

                    // 2. Extract User Name
                    val nameRegex = Regex("""(?:ALERT!\s*(.*?)\s*needs help|UPDATE\s*(.*?):)""", RegexOption.IGNORE_CASE)
                    val nameMatch = nameRegex.find(cleanBody)
                    val userName = nameMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                        ?: nameMatch?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
                        ?: "Guardian ($sender)"

                    // 3. Extract Situation Summary
                    val summaryRegex = Regex("""(?:Summary:\s*(.*)|UPDATE\s*[^:]*:\s*(.*))""", RegexOption.IGNORE_CASE)
                    val summaryMatch = summaryRegex.find(cleanBody)
                    val situationSummary = summaryMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                        ?: summaryMatch?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
                        ?: cleanBody

                    // 4. Extract Timestamp and Sender Phone
                    val timeRegex = Regex("""Time:\s*([^\n|]+)""", RegexOption.IGNORE_CASE)
                    val timeMatch = timeRegex.find(cleanBody)?.groupValues?.get(1)?.trim()
                    val formattedTime = timeMatch ?: SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(smsTimeMillis))

                    val phoneRegex = Regex("""Ph:\s*([^\n|]+)""", RegexOption.IGNORE_CASE)
                    val phoneMatch = phoneRegex.find(cleanBody)?.groupValues?.get(1)?.trim()
                    val userPhone = phoneMatch ?: sender

                    // 5. Extract Supabase photo links (PIC1:/PIC2: + any bare image URL)
                    val picTagged = Regex("""PIC\d?:\s*(https?://\S+)""", RegexOption.IGNORE_CASE)
                        .findAll(cleanBody).map { it.groupValues[1].trimEnd('.', ',', ')') }.toList()
                    val picBare = Regex("""https?://\S*emergency-photos\S*""", RegexOption.IGNORE_CASE)
                        .findAll(cleanBody).map { it.value.trimEnd('.', ',', ')') }.toList()
                    val photoUrls = (picTagged + picBare).distinct()
                    val frontPhotoUrl = photoUrls.getOrNull(0) ?: ""
                    val backPhotoUrl = photoUrls.getOrNull(1) ?: ""
                    if (photoUrls.isNotEmpty()) Log.d(TAG, "Found ${photoUrls.size} photo link(s) in SMS")

                    // Check if this is a CANCEL ALERT SMS
                    if (cleanBody.contains("CANCEL ALERT", ignoreCase = true) || cleanBody.contains("is SAFE", ignoreCase = true)) {
                        Log.d(TAG, "✅ EMERGENCY CANCELLED: $userName is SAFE!")
                        AlarmSoundPlayer.stopAlarm(context)

                        val viewerIntent = Intent(context, EmergencyAlertViewerActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("fullName", userName)
                            putExtra("phoneNumber", userPhone)
                            putExtra("situationSummary", "User is SAFE. Situation resolved / False alarm.")
                            putExtra("timestamp", formattedTime)
                            putExtra("isCancelled", true)
                            putExtra("isSmsAlert", true)
                        }
                        try {
                            context.startActivity(viewerIntent)
                        } catch (e: Exception) {
                            Log.w(TAG, "Launch viewer for cancel alert: ${e.message}")
                        }
                        return
                    }

                    // Check if this is a GUARDIAN ACKNOWLEDGED SMS (sent to user)
                    if (cleanBody.contains("GUARDIAN ACKNOWLEDGED", ignoreCase = true)) {
                        Log.d(TAG, "🛡️ GUARDIAN ACKNOWLEDGEMENT RECEIVED")
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        val channelId = "emergency_alarm_siren_channel_v3"
                        val ackNotif = androidx.core.app.NotificationCompat.Builder(context, channelId)
                            .setSmallIcon(com.sriox.vasateysec.R.mipmap.ic_launcher)
                            .setContentTitle("Guardian Confirmed Alert")
                            .setContentText("Help is on the way! Alert acknowledged by Guardian.")
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true)
                            .build()
                        notificationManager.notify(7777, ackNotif)
                        return
                    }

                    val alertData = mapOf(
                        "fullName" to userName,
                        "phoneNumber" to userPhone,
                        "latitude" to latitude,
                        "longitude" to longitude,
                        "situationSummary" to situationSummary,
                        "email" to situationSummary,
                        "timestamp" to formattedTime,
                        "frontPhotoUrl" to frontPhotoUrl,
                        "backPhotoUrl" to backPhotoUrl,
                        "isSmsAlert" to "true"
                    )

                    Log.d(TAG, "Parsed SMS Alert: lat=$latitude, lon=$longitude, name=$userName, time=$formattedTime, summary=$situationSummary")

                    // Save incoming alert to local history
                    try {
                        val history = com.sriox.vasateysec.models.AlertHistory(
                            id = "sms_${System.currentTimeMillis()}",
                            user_id = userPhone,
                            user_name = userName,
                            user_email = situationSummary,
                            user_phone = userPhone,
                            latitude = latitude.toDoubleOrNull(),
                            longitude = longitude.toDoubleOrNull(),
                            location_accuracy = 10f,
                            alert_type = "emergency_sms",
                            status = "received",
                            created_at = formattedTime,
                            front_photo_url = frontPhotoUrl.ifBlank { null },
                            back_photo_url = backPhotoUrl.ifBlank { null }
                        )
                        AlertManager.saveAlertToLocal(context, history)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to save alert to local history: ${e.message}")
                    }

                    // 5. Trigger Loud Siren Alarm + Auto-Launch Alert Viewer with DND bypass
                    AlarmSoundPlayer.startAlarm(
                        context,
                        "EMERGENCY ALERT: $userName",
                        situationSummary,
                        alertData
                    )
        }
    }
}
