package com.sriox.vasateysec.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sriox.vasateysec.EmergencyAlertViewerActivity
import com.sriox.vasateysec.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-Volume Emergency Alarm Siren & Auto-Launch Manager.
 * Bypasses Silent/DND modes, plays a high-decibel alternating beep-beep siren,
 * and auto-launches the full Alert Viewer with Google Maps navigation on guardian mobile.
 */
object AlarmSoundPlayer {

    private const val TAG = "AlarmSoundPlayer"
    private const val CHANNEL_ID = "emergency_alarm_siren_channel_v3"
    private const val NOTIFICATION_ID = 9999
    const val ACTION_STOP_ALARM = "com.sriox.vasateysec.STOP_ALARM"

    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var sirenJob: Job? = null

    /**
     * Starts playing a loud beep-beep siren alarm, overrides silent mode, and auto-launches alert viewer.
     */
    fun startAlarm(
        context: Context,
        title: String,
        message: String,
        alertData: Map<String, String> = emptyMap()
    ) {
        val prefs = context.getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)
        val isLoudAlarmEnabled = prefs.getBoolean("loud_alarm_enabled", true)

        if (!isLoudAlarmEnabled) {
            Log.d(TAG, "Loud Alarm disabled by user in Settings.")
            return
        }

        Log.d(TAG, "🚨 TRIGGERING LOUD SIREN ALARM: $title | Data: $alertData")

        try {
            // 1. Force Maximize Audio Volume & Override Silent/DND
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Request audio focus with exclusive transient gain to cut through media/silence
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            }

            val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)

            val maxRingVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRingVol, 0)

            // 2. Stop any previous alarm instance
            stopAlarm(context)

            // 3. Play High-Decibel Beep-Beep Siren
            startBeepSirenLoop(context)

            // 4. Vibrate in rapid emergency pattern
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 300, 100, 300, 100, 300, 100, 300, 100, 300, 100, 300, 100, 300, 100, 300, 100, 300, 100, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }

            // 5. Show Full-Screen Auto-Launch Emergency Notification
            showAlarmNotificationAndAutoLaunch(context, title, message, alertData)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start emergency siren alarm", e)
        }
    }

    /**
     * Synthesizes a piercing 4-second dual-tone emergency siren (950 Hz & 1550 Hz alternating wails)
     * using AudioTrack on USAGE_ALARM with FLAG_AUDIBILITY_ENFORCED.
     */
    private fun startBeepSirenLoop(context: Context) {
        sirenJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                val sampleRate = 44100
                val durationSeconds = 4.2
                val totalSamples = (sampleRate * durationSeconds).toInt()
                val sirenBuffer = ShortArray(totalSamples)

                // Alternating high-urgency emergency tones: 950 Hz & 1550 Hz alternating every 250ms
                val pulseSamples = (sampleRate * 0.25).toInt()
                for (i in 0 until totalSamples) {
                    val pulseIndex = (i / pulseSamples) % 2
                    val baseFreq = if (pulseIndex == 0) 950.0 else 1550.0
                    val angle = 2.0 * Math.PI * baseFreq * i / sampleRate
                    sirenBuffer[i] = (Math.sin(angle) * 32000.0).toInt().toShort()
                }

                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(sirenBuffer.size * 2)

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()

                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                val track = AudioTrack(
                    attributes,
                    format,
                    minBuf,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                audioTrack = track
                track.play()

                // Write 4.2 seconds siren audio
                var offset = 0
                val chunkSize = 4096
                while (isActive && offset < sirenBuffer.size) {
                    val count = Math.min(chunkSize, sirenBuffer.size - offset)
                    val written = track.write(sirenBuffer, offset, count)
                    if (written > 0) {
                        offset += written
                    } else {
                        break
                    }
                }

                // Short delay to allow remaining buffer to play out, then clean up
                kotlinx.coroutines.delay(500)
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
                audioTrack = null
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack failed, falling back to Ringtone: ${e.message}")
                fallbackRingtone(context)
            }
        }
    }

    private fun generateSineWave(freq: Double, samplesCount: Int, sampleRate: Int): ShortArray {
        val result = ShortArray(samplesCount)
        for (i in 0 until samplesCount) {
            val angle = 2.0 * Math.PI * i * freq / sampleRate
            result[i] = (Math.sin(angle) * 32000.0).toInt().toShort()
        }
        return result
    }

    private fun fallbackRingtone(context: Context) {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    /**
     * Stops the siren sound, vibration, and removes the alarm notification.
     */
    fun stopAlarm(context: Context) {
        try {
            sirenJob?.cancel()
            sirenJob = null

            audioTrack?.let {
                try {
                    it.stop()
                    it.release()
                } catch (_: Exception) {}
            }
            audioTrack = null

            mediaPlayer?.let {
                try {
                    it.stop()
                    it.release()
                } catch (_: Exception) {}
            }
            mediaPlayer = null

            vibrator?.cancel()
            vibrator = null

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)

            Log.d(TAG, "Emergency Alarm Stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm", e)
        }
    }

    private fun showAlarmNotificationAndAutoLaunch(
        context: Context,
        title: String,
        message: String,
        alertData: Map<String, String>
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Critical Emergency Siren",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-Priority Emergency Siren Alerts"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                setBypassDnd(true) // Bypasses Do Not Disturb
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null) // Sound is handled by our AudioTrack siren
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Full Screen Intent: Auto-opens EmergencyAlertViewerActivity with map & details on Guardian's phone!
        val viewerIntent = Intent(context, EmergencyAlertViewerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("fullName", alertData["fullName"] ?: alertData["fromName"] ?: title.replace("EMERGENCY SMS ALERT from", "").trim())
            putExtra("phoneNumber", alertData["phoneNumber"] ?: alertData["fromPhone"] ?: alertData["senderPhone"] ?: "")
            putExtra("email", alertData["email"] ?: alertData["situationSummary"] ?: "Emergency Alert")
            putExtra("situationSummary", alertData["situationSummary"] ?: "")
            putExtra("latitude", alertData["latitude"] ?: alertData["lastKnownLatitude"] ?: "")
            putExtra("longitude", alertData["longitude"] ?: alertData["lastKnownLongitude"] ?: "")
            putExtra("alertId", alertData["alertId"] ?: "")
            putExtra("timestamp", alertData["timestamp"] ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))
            putExtra("fromNotification", true)
            putExtra("isSmsAlert", alertData["isSmsAlert"] == "true")
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            viewerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action Intent to Mute Siren
        val stopIntent = Intent(context, AlarmStopReceiver::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true) // Auto-wake & open on lockscreen
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "MUTE SIREN", stopPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // Directly launch the activity on guardian phone
        try {
            context.startActivity(viewerIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct activity launch: ${e.message}")
        }
    }
}
