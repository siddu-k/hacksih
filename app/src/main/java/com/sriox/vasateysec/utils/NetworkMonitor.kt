package com.sriox.vasateysec.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executor

/**
 * Mobile Signal Strength Meter & Network Connection Monitor.
 * Provides real-time cellular signal meter readings (0-4 bars) and
 * triggers AlertQueueManager.flushQueue() whenever network connectivity is regained.
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"

    data class SignalInfo(
        val level: Int, // 0 = No Signal, 1 = Poor, 2 = Moderate, 3 = Good, 4 = Great
        val label: String,
        val isOnline: Boolean
    )

    private var currentLevel: Int = 0
    private var isNetworkAvailable: Boolean = false
    private var telephonyCallback: Any? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Checks if cellular signal or internet is currently available.
     */
    fun isCellularOrNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true // assume available if permission restricted
        }
    }

    /**
     * Gets instantaneous signal level (0 to 4).
     */
    fun getCurrentSignalLevel(context: Context): Int {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tm?.signalStrength?.level ?: currentLevel
            } else {
                currentLevel
            }
        } catch (_: Exception) {
            currentLevel
        }
    }

    /**
     * Starts listening for mobile signal strength and network connectivity changes.
     */
    fun startMonitoring(context: Context, onSignalChanged: (SignalInfo) -> Unit) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        // 1. Initial status reading
        isNetworkAvailable = isCellularOrNetworkAvailable(context)
        currentLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { tm?.signalStrength?.level ?: 0 } catch (_: Exception) { 2 }
        } else {
            2
        }

        emitSignalInfo(onSignalChanged)

        // 2. Cellular Signal Strength Monitor
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        currentLevel = signalStrength.level
                        emitSignalInfo(onSignalChanged)
                    }
                }
                val mainExecutor: Executor = context.mainExecutor
                tm?.registerTelephonyCallback(mainExecutor, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                        super.onSignalStrengthsChanged(signalStrength)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            currentLevel = signalStrength?.level ?: 0
                        }
                        emitSignalInfo(onSignalChanged)
                    }
                }
                @Suppress("DEPRECATION")
                tm?.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
                telephonyCallback = listener
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register telephony signal listener: ${e.message}")
        }

        // 3. Network Connectivity Monitor (Triggers AlertQueueManager on reconnection)
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "📶 Network is available! Triggering queue flush...")
                    isNetworkAvailable = true
                    emitSignalInfo(onSignalChanged)
                    // Auto-flush pending emergency alerts
                    AlertQueueManager.flushQueue(context)
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.w(TAG, "⚠️ Network lost! Alerts will be queued locally.")
                    isNetworkAvailable = false
                    emitSignalInfo(onSignalChanged)
                }
            }

            cm?.registerNetworkCallback(request, netCallback)
            networkCallback = netCallback
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun emitSignalInfo(onSignalChanged: (SignalInfo) -> Unit) {
        val label = when {
            !isNetworkAvailable || currentLevel == 0 -> "No Signal (Queuing Active)"
            currentLevel == 1 -> "Weak Signal (1/4)"
            currentLevel == 2 -> "Moderate Signal (2/4)"
            currentLevel == 3 -> "Good Signal (3/4)"
            else -> "Excellent Signal (4/4)"
        }
        val info = SignalInfo(
            level = currentLevel,
            label = label,
            isOnline = isNetworkAvailable && currentLevel > 0
        )
        onSignalChanged(info)
    }

    /**
     * Unregisters signal and network listeners.
     */
    fun stopMonitoring(context: Context) {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback is TelephonyCallback) {
                tm?.unregisterTelephonyCallback(telephonyCallback as TelephonyCallback)
            } else if (telephonyCallback is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm?.listen(telephonyCallback as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
            telephonyCallback = null

            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping network monitor: ${e.message}")
        }
    }
}
