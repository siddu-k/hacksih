package com.sriox.vasateysec.utils

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Spoken SOS confirmations ("Help detected…") via on-device TTS.
 * App-scoped singleton: first call initializes, pending phrase is
 * spoken as soon as the engine is ready. Never throws.
 */
object VoiceFeedback {

    private const val TAG = "VoiceFeedback"

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var ttsLang: String? = null
    private var pending: String? = null
    private var pendingLang: String = VoiceLanguage.EN
    private val lock = Any()

    /** "Help detected. Sending emergency alert." in the user's voice language. */
    fun speakDetection(context: Context) {
        val lang = VoiceLanguage.get(context.applicationContext)
        val text = if (lang == VoiceLanguage.TE)
            "సహాయం గుర్తించబడింది. అత్యవసర హెచ్చరిక పంపుతున్నాము."
        else
            "Help detected. Sending emergency alert."
        speak(context, text, lang)
    }

    /** Manual SOS button confirmation in the user's voice language. */
    fun speakManualSos(context: Context) {
        val lang = VoiceLanguage.get(context.applicationContext)
        val text = if (lang == VoiceLanguage.TE)
            "అత్యవసర హెచ్చరిక పంపుతున్నాము."
        else
            "Sending emergency alert."
        speak(context, text, lang)
    }

    fun speak(context: Context, text: String, lang: String = VoiceLanguage.EN) {
        try {
            val app = context.applicationContext
            synchronized(lock) {
                if (ready && tts != null && ttsLang == lang) {
                    speakNow(text)
                    return
                }
                pending = text
                pendingLang = lang
                if (tts == null) {
                    tts = TextToSpeech(app) { status ->
                        synchronized(lock) {
                            if (status == TextToSpeech.SUCCESS) {
                                applyLanguageLocked()
                                ready = true
                                pending?.let { speakNow(it) }
                                pending = null
                            } else {
                                Log.w(TAG, "TTS init failed: $status")
                                tts = null
                                pending = null
                            }
                        }
                    }
                } else if (ttsLang != lang) {
                    // Engine alive but wrong language: switch + speak when ready.
                    ready = false
                    try {
                        applyLanguageLocked()
                        ready = true
                        pending?.let { speakNow(it) }
                        pending = null
                    } catch (e: Exception) {
                        Log.w(TAG, "TTS language switch failed: ${e.message}")
                        pending = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "speak failed (non-fatal): ${e.message}")
        }
    }

    private fun applyLanguageLocked() {
        val t = tts ?: return
        val locale = if (pendingLang == VoiceLanguage.TE) Locale("te", "IN") else Locale.getDefault()
        val res = t.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.language = if (pendingLang == VoiceLanguage.TE) Locale.ENGLISH else Locale.getDefault()
        }
        t.setSpeechRate(1.0f)
        ttsLang = pendingLang
    }

    private fun speakNow(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "sos_confirm")
        } catch (e: Exception) {
            Log.w(TAG, "speakNow failed (non-fatal): ${e.message}")
        }
    }
}
