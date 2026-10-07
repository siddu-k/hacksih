package com.sriox.vasateysec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sriox.vasateysec.utils.AlertManager
import com.sriox.vasateysec.utils.CameraManager
import com.sriox.vasateysec.utils.LocationManager
import com.sriox.vasateysec.utils.VoiceFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskWakeWordService : Service(), RecognitionListener {

    companion object {
        var sharedModel: Model? = null
    }

    private var speechService: SpeechService? = null
    private var lastRecognitionTime: Long = 0
    private var lastAlertTime: Long = 0
    private val cooldownPeriod = 5000 
    private var isWaitingForSecondWord = false
    private var firstWordDetectedTime: Long = 0
    private val doubleWordWindow = 5000 
    private val NOTIFICATION_ID = 1
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var reconnectCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        
        // Acquire PARTIAL_WAKE_LOCK to keep CPU awake for continuous voice monitoring when screen is turned off
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Vasateysec:WakeWordWakeLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("VoskService", "Acquired PARTIAL_WAKE_LOCK for screen-off continuous voice listening")
        } catch (e: Exception) {
            Log.w("VoskService", "Failed to acquire WakeLock: ${e.message}")
        }

        initVosk()
        startWatchdog()
        startBackgroundQueueFlush()
    }

    /**
     * HomeActivity's NetworkMonitor only lives while the app is open.
     * This service runs 24/7, so it registers its own reconnect listener:
     * any queued SOS auto-sends the moment cellular/data returns,
     * even with the app closed.
     */
    private fun startBackgroundQueueFlush() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    super.onAvailable(network)
                    Log.d("VoskService", "📶 Reconnected in background — flushing queued SOS alerts")
                    com.sriox.vasateysec.utils.AlertQueueManager.flushQueue(this@VoskWakeWordService)
                }
            }
            cm.registerNetworkCallback(request, callback)
            reconnectCallback = callback
        } catch (e: Exception) {
            Log.w("VoskService", "Background queue-flush hook failed (non-fatal): ${e.message}")
        }
    }

    private fun initVosk() {
        Log.d("VoskService", "Initializing Vosk Engine...")
        Thread {
            val lang = com.sriox.vasateysec.utils.VoiceLanguage.get(this)
            val rawWake = com.sriox.vasateysec.utils.VoiceLanguage.getWakeWord(this)
            val wakeWord = com.sriox.vasateysec.utils.VoiceLanguage.sanitizeForGrammar(rawWake, lang)
            Log.d("VoskService", "Voice language=$lang wakeWord=$wakeWord")

            // Telugu + model downloaded → use it; otherwise fall back to bundled English.
            if (lang == com.sriox.vasateysec.utils.VoiceLanguage.TE &&
                com.sriox.vasateysec.utils.VoskModelManager.isTeluguReady(this)
            ) {
                try {
                    try { sharedModel?.close() } catch (_: Exception) {}
                    val teModel = Model(
                        com.sriox.vasateysec.utils.VoskModelManager.teDir(this).absolutePath
                    )
                    sharedModel = teModel
                    startWithModel(teModel, wakeWord)
                } catch (e: Exception) {
                    Log.w("VoskService", "Telugu model failed (${e.message}), falling back to English")
                    initEnglishModel(wakeWord)
                }
            } else {
                if (lang == com.sriox.vasateysec.utils.VoiceLanguage.TE) {
                    Log.w("VoskService", "Telugu selected but model not downloaded — using English until download finishes")
                }
                initEnglishModel(
                    if (lang == com.sriox.vasateysec.utils.VoiceLanguage.TE)
                        com.sriox.vasateysec.utils.VoiceLanguage.DEFAULT_EN_WAKE else wakeWord
                )
            }
        }.start()
    }

    private fun initEnglishModel(wakeWord: String) {
        StorageService.unpack(this, "model", "model",
            { model: Model? ->
                try { sharedModel?.close() } catch (_: Exception) {}
                sharedModel = model
                try {
                    startWithModel(model, wakeWord)
                } catch (e: IOException) {
                    Log.e("VoskService", "Recognizer initialization failed", e)
                }
            },
            { exception: IOException ->
                Log.e("VoskService", "Failed to unpack model", exception)
            })
    }

    @Throws(IOException::class)
    private fun startWithModel(model: Model?, wakeWord: String) {
        // Escape for JSON grammar array: ["wake word", "[unk]"]
        val escaped = wakeWord.replace("\\", "\\\\").replace("\"", "\\\"")
        val recognizer = Recognizer(model, 16000f, "[\"$escaped\", \"[unk]\"]")
        speechService = SpeechService(recognizer, 16000f)
        startListening()
    }

    private fun startListening() {
        try {
            speechService?.startListening(this)
            isListening = true
            Log.d("VoskService", "Voice recognition started")
        } catch (e: Exception) {
            Log.e("VoskService", "Failed to start listening: ${e.message}")
            retryListening()
        }
    }

    private fun retryListening() {
        isListening = false
        mainHandler.postDelayed({
            Log.d("VoskService", "Attempting to restart listener...")
            startListening()
        }, 3000)
    }

    // Watchdog to ensure we never stop listening permanently
    private fun startWatchdog() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isListening) {
                    Log.w("VoskService", "Watchdog: Engine not listening, restarting...")
                    startListening()
                }
                mainHandler.postDelayed(this, 20000) // Check every 20 seconds
            }
        }, 20000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Safety Guardian", "Continuous voice monitoring active"))
        return START_STICKY
    }

    private fun updateNotification(title: String, text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(title, text))
    }

    private fun createNotification(title: String, contentText: String): Notification {
        val channelId = "VOSK_SERVICE_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Wake Word Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        processHypothesis(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        processHypothesis(hypothesis)
    }

    private fun processHypothesis(hypothesis: String?) {
        hypothesis?.let {
            val resultText = getResultTextFromJson(it)
            if (resultText.isNotBlank()) {
                Log.d("VoskService", "Recognized: $resultText")
                val currentTime = SystemClock.elapsedRealtime()
                val settings = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
                val isDoubleWordEnabled = settings.getBoolean("double_word_enabled", true)
                val lang = com.sriox.vasateysec.utils.VoiceLanguage.get(this@VoskWakeWordService)
                val wakeWord = com.sriox.vasateysec.utils.VoiceLanguage.getWakeWord(this@VoskWakeWordService)

                if (com.sriox.vasateysec.utils.VoiceLanguage.matches(resultText, wakeWord, lang)) {
                    if (isDoubleWordEnabled) {
                        handleDoubleWordDetection(currentTime, wakeWord)
                    } else {
                        if (currentTime - lastRecognitionTime > cooldownPeriod) {
                            lastRecognitionTime = currentTime
                            triggerAlertSequence()
                        }
                    }
                }
            }
        }
    }

    private fun handleDoubleWordDetection(currentTime: Long, wakeWord: String) {
        if (!isWaitingForSecondWord) {
            isWaitingForSecondWord = true
            firstWordDetectedTime = currentTime
            Log.d("VoskService", "First detection. Waiting for second...")
            updateNotification("Alert Triggered", "Wake word detected once. Waiting for second...")
        } else {
            if (currentTime - firstWordDetectedTime <= doubleWordWindow) {
                isWaitingForSecondWord = false
                triggerAlertSequence()
            } else {
                // Window expired, set this as the new first word
                firstWordDetectedTime = currentTime
            }
        }
    }

    private fun triggerAlertSequence() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastAlertTime < 5000L) {
            Log.d("VoskService", "Alert throttled: triggered within 5-second window")
            return
        }
        lastAlertTime = currentTime
        // Instant voice confirmation on this phone so the user knows SOS fired.
        VoiceFeedback.speakDetection(this)
        updateNotification("SOS ACTIVATED", "Initiating emergency sequence...")
        triggerEmergencyAlert()
    }
    
    private fun triggerEmergencyAlert() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateNotification("SOS: Processing", "Getting location...")
                val location = LocationManager.getCurrentLocation(this@VoskWakeWordService)

                // Unconditionally capture evidence photos (saved locally to emergency_evidence folder)
                updateNotification("SOS: Processing", "Capturing evidence photos...")
                val photos = CameraManager.captureEmergencyPhotos(this@VoskWakeWordService)

                // Dispatch offline SMS Alert and/or Auto Call
                updateNotification("SOS: Sending", "Dispatching emergency alerts...")
                AlertManager.sendEmergencyAlert(
                    context = this@VoskWakeWordService,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    locationAccuracy = location?.accuracy,
                    frontPhotoFile = photos.frontPhoto,
                    backPhotoFile = photos.backPhoto,
                    onAlertCreated = { _ -> }
                )

                updateNotification("SOS SENT", "Emergency alerts dispatched successfully.")

                val triggerIntent = Intent("com.sriox.vasateysec.ALERT_TRIGGERED")
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this@VoskWakeWordService).sendBroadcast(triggerIntent)
                sendBroadcast(triggerIntent)

                kotlinx.coroutines.delay(10000)
                updateNotification("Safety Guardian", "Continuous voice monitoring active")

            } catch (e: Exception) {
                Log.e("VoskService", "Trigger error: ${e.message}")
                updateNotification("SOS ERROR", "Restarting listener...")
                kotlinx.coroutines.delay(5000)
                updateNotification("Safety Guardian", "Continuous voice monitoring active")
            }
        }
    }

    private fun getResultTextFromJson(json: String): String {
        return try {
            // Vosk emits {"text": "..."} (no guaranteed spacing) — parse properly
            // so Telugu script isn't mangled by naive substring logic.
            val obj = org.json.JSONObject(json)
            // Prefer "text"; fall back to "partial" for partial-result callbacks.
            val t = obj.optString("text", "")
            if (t.isNotBlank()) t else obj.optString("partial", "")
        } catch (e: Exception) { "" }
    }

    override fun onError(exception: Exception?) {
        Log.e("VoskService", "Vosk Error: ${exception?.message}")
        retryListening()
    }

    override fun onTimeout() {
        Log.d("VoskService", "Vosk Timeout - restarting...")
        startListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            reconnectCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                    ?.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        reconnectCallback = null
        speechService?.stop()
        speechService?.shutdown()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d("VoskService", "Released PARTIAL_WAKE_LOCK")
                }
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
