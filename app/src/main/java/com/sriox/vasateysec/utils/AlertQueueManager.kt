package com.sriox.vasateysec.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline Alert Queue Manager
 * Persists emergency alerts that fail to send due to no cellular network / low signal.
 * Automatically retries and flushes queued alerts when mobile connectivity is restored.
 */
object AlertQueueManager {

    private const val TAG = "AlertQueueManager"
    private const val PREFS_NAME = "alert_offline_queue"
    private const val KEY_QUEUE = "pending_alerts_json"
    const val ACTION_QUEUE_UPDATED = "com.sriox.vasateysec.QUEUE_UPDATED"

    private val mutex = Mutex()
    private var isFlushing = false

    @Serializable
    data class QueuedAlert(
        val id: String,
        val latitude: Double?,
        val longitude: Double?,
        val timestamp: String,
        val situationSummary: String? = null,
        val isHardware: Boolean = false,
        var retryCount: Int = 0,
        val queuedAtMillis: Long = System.currentTimeMillis()
    )

    fun getQueuedAlerts(context: Context): List<QueuedAlert> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing queue: ${e.message}")
            emptyList()
        }
    }

    fun getQueueCount(context: Context): Int {
        return getQueuedAlerts(context).size
    }

    suspend fun enqueueAlert(
        context: Context,
        latitude: Double?,
        longitude: Double?,
        situationSummary: String? = null,
        isHardware: Boolean = false
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = getQueuedAlerts(context).toMutableList()
            val id = "queue_${System.currentTimeMillis()}"
            val timeStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())

            val alert = QueuedAlert(
                id = id,
                latitude = latitude,
                longitude = longitude,
                timestamp = timeStr,
                situationSummary = situationSummary,
                isHardware = isHardware
            )

            // Prevent excessive queue size
            if (list.size >= 25) {
                list.removeAt(0)
            }
            list.add(alert)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_QUEUE, Json.encodeToString(list)).apply()

            Log.w(TAG, "📥 Enqueued Alert [$id] due to offline/no-service. Queue size: ${list.size}")
            notifyQueueUpdated(context)
        }
    }

    suspend fun removeAlert(context: Context, alertId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = getQueuedAlerts(context).toMutableList()
            list.removeAll { it.id == alertId }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_QUEUE, Json.encodeToString(list)).apply()
            notifyQueueUpdated(context)
        }
    }

    suspend fun clearQueue(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_QUEUE).apply()
            notifyQueueUpdated(context)
            Log.d(TAG, "Queue cleared")
        }
    }

    /**
     * Called when cellular/network connectivity is restored.
     * Iterates through pending queued alerts and dispatches them via SMS.
     */
    fun flushQueue(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (isFlushing) return@launch
                val queue = getQueuedAlerts(context)
                if (queue.isEmpty()) return@launch

                isFlushing = true
                Log.d(TAG, "🔄 Network restored! Flushing ${queue.size} pending alerts in queue...")
            }

            try {
                val alerts = getQueuedAlerts(context)
                for (alert in alerts) {
                    try {
                        Log.d(TAG, "Dispatching queued alert: ${alert.id}")
                        // Send SMS without hitting universal cooldown check
                        SmsHelper.sendEmergencySms(
                            context = context,
                            latitude = alert.latitude,
                            longitude = alert.longitude,
                            isHardware = alert.isHardware,
                            situationSummary = alert.situationSummary,
                            isQueuedRetry = true
                        )
                        removeAlert(context, alert.id)
                        // Brief pause between queued SMS sends
                        kotlinx.coroutines.delay(1000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send queued alert ${alert.id}: ${e.message}")
                    }
                }
            } finally {
                mutex.withLock { isFlushing = false }
                notifyQueueUpdated(context)
            }
        }
    }

    private fun notifyQueueUpdated(context: Context) {
        val intent = Intent(ACTION_QUEUE_UPDATED)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        context.sendBroadcast(intent)
    }
}
