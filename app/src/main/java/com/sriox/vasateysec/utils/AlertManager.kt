package com.sriox.vasateysec.utils

import android.content.Context
import android.util.Log
import com.sriox.vasateysec.models.AlertHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object AlertManager {
    private const val TAG = "AlertManager"
    private const val PREFS_NAME = "local_alert_history_prefs"
    private const val KEY_HISTORY = "local_alert_history_json"

    /**
     * Send emergency alert to all guardians via SMS and local photo storage
     */
    suspend fun sendEmergencyAlert(
        context: Context,
        latitude: Double?,
        longitude: Double?,
        locationAccuracy: Float? = null,
        frontPhotoFile: File? = null,
        backPhotoFile: File? = null,
        situationSummary: String? = null,
        onAlertCreated: ((alertId: String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "sendEmergencyAlert called: lat=$latitude, lon=$longitude, accuracy=$locationAccuracy")
            
            val alertId = UUID.randomUUID().toString()
            val userId = SessionManager.getUserId() ?: "local_user"
            val userName = SessionManager.getUserName() ?: "Safety Guardian"
            val userPhone = SessionManager.getUserPhone() ?: ""
            val userEmail = SessionManager.getUserEmail() ?: ""

            onAlertCreated?.invoke(alertId)

            val nowStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

            val history = AlertHistory(
                id = alertId,
                user_id = userId,
                user_name = userName,
                user_email = userEmail,
                user_phone = userPhone,
                latitude = latitude,
                longitude = longitude,
                location_accuracy = locationAccuracy,
                alert_type = "voice_help",
                status = "sent",
                created_at = nowStr,
                front_photo_url = frontPhotoFile?.absolutePath,
                back_photo_url = backPhotoFile?.absolutePath
            )

            // Save to local device storage
            saveAlertToLocal(context, history)

            // Dispatch emergency SMS immediately
            SmsHelper.sendEmergencySms(
                context = context,
                latitude = latitude,
                longitude = longitude,
                isHardware = false,
                situationSummary = situationSummary
            )

            Result.success(alertId)
        } catch (e: Exception) {
            Log.e(TAG, "sendEmergencyAlert error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Follow-up situation summary to Emergency SMS
     */
    suspend fun sendSummaryFollowUp(
        context: Context,
        alertId: String,
        summaryBody: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Dispatching SMS summary follow-up for alert $alertId")
            SmsHelper.sendSummarySms(
                context = context,
                latitude = null,
                longitude = null,
                summaryText = summaryBody
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendSummaryFollowUp failed: ${e.message}", e)
            false
        }
    }

    /**
     * Save an alert record to local SharedPreferences
     */
    fun saveAlertToLocal(context: Context, alert: AlertHistory) {
        try {
            val list = getLocalAlertHistory(context).toMutableList()
            list.add(0, alert)
            // Keep up to 50 alerts locally
            val trimmed = list.take(50)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Json.encodeToString(trimmed)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving local alert: ${e.message}")
        }
    }

    /**
     * Read all local alert history
     */
    fun getLocalAlertHistory(context: Context): List<AlertHistory> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_HISTORY, null)
            if (json != null) {
                Json.decodeFromString(json)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Clear all local alert history
     */
    fun clearLocalAlertHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
