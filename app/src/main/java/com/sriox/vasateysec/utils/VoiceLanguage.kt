package com.sriox.vasateysec.utils

import android.content.Context

/**
 * Voice interaction language: English (Vosk model bundled in APK)
 * or Telugu (model downloaded on demand into filesDir/model_te).
 */
object VoiceLanguage {

    const val EN = "en"
    const val TE = "te"

    private const val PREFS = "vasatey_prefs"
    private const val KEY_LANG = "voice_language"
    private const val KEY_WAKE = "wake_word"

    const val DEFAULT_EN_WAKE = "help me"
    const val DEFAULT_TE_WAKE = "సహాయం"

    val DISPLAY_NAMES = arrayOf("English", "తెలుగు (Telugu)")
    val CODES = arrayOf(EN, TE)

    fun get(context: Context): String {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANG, EN) ?: EN
        } catch (_: Exception) { EN }
    }

    fun set(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
    }

    fun defaultWakeWord(lang: String): String =
        if (lang == TE) DEFAULT_TE_WAKE else DEFAULT_EN_WAKE

    /**
     * Normalize a wake word for storage/comparison:
     * - strip quotes/backslashes (would break Vosk JSON grammar)
     * - lowercase ONLY Latin letters so Telugu script (సహాయం) is preserved as-is.
     */
    fun normalizeWakeWord(raw: String, lang: String): String {
        var clean = raw.replace("\"", "").replace("\\", "").trim()
        if (lang != TE) clean = clean.lowercase(java.util.Locale.ROOT)
        return clean
    }

    fun setWakeWord(context: Context, wakeWord: String) {
        val lang = get(context)
        val clean = normalizeWakeWord(wakeWord, lang).ifBlank { defaultWakeWord(lang) }
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_WAKE, clean).apply()
        } catch (_: Exception) {}
    }

    fun getWakeWord(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lang = prefs.getString(KEY_LANG, EN) ?: EN
            prefs.getString(KEY_WAKE, defaultWakeWord(lang)) ?: defaultWakeWord(lang)
        } catch (_: Exception) { DEFAULT_EN_WAKE }
    }

    /** Grammar-safe: strip quotes (would break Vosk JSON grammar). */
    fun sanitizeForGrammar(wakeWord: String, lang: String): String {
        val clean = normalizeWakeWord(wakeWord, lang)
        return clean.ifBlank { defaultWakeWord(lang) }
    }

    /**
     * True when ASR text contains the wake word.
     * English → case-insensitive; Telugu → exact substring (script has no case,
     * and ignoreCase=true can miss/mangle non-Latin matches on some devices).
     */
    fun matches(resultText: String, wakeWord: String, lang: String): Boolean {
        if (wakeWord.isBlank() || resultText.isBlank()) return false
        return if (lang == TE) resultText.contains(wakeWord)
        else resultText.contains(wakeWord, ignoreCase = true)
    }
}
