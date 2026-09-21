package com.sriox.vasateysec.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Fast on-device situation summarizer for post-wake-word 10s clips.
 *
 * Why not Phi-3-mini here:
 * - 2.4GB download, needs 4GB+ free + 6GB RAM flagship
 * - GGUF needs full llama.cpp engine (current C++ is a stub)
 * - 15-30s inference on phone CPU = too slow for emergency
 *
 * This path is: 10s PCM (~320KB) -> Vosk STT (<1s) -> keyword summarizer (<50ms).
 * Zero download, works on all devices, honest offline.
 *
 * Future upgrade path: swap summarize() internals with MediaPipe LLM
 * (Gemma3-270M / SmolLM2-360M .task, ~300MB) for abstractive summaries.
 */
object SituationSummarizer {

    private const val TAG = "SituationSummarizer"
    private const val SAMPLE_RATE = 16000
    private const val RECORD_SECONDS = 10
    const val MAX_TRANSCRIPT_CHARS = 300

    data class SituationResult(
        val transcript: String,
        val summary: String,
        val urgency: String, // HIGH, MEDIUM, LOW
        val category: String
    )

    /**
     * Record 10s mono 16kHz PCM after wake word. Non-blocking caller expected.
     * Returns wav file in cache, or null on failure. Never throws.
     *
     * Lint note: RECORD_AUDIO is checked explicitly below; @SuppressLint covers
     * lint's inability to track the check across the Dispatchers.IO boundary.
     * A SecurityException catch guards runtime revocation mid-record.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordTenSecondClip(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "RECORD_AUDIO not granted, skipping clip")
                return@withContext null
            }

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(32 * 1024)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord init failed")
                return@withContext null
            }

            val outFile = File(context.cacheDir, "situation_${System.currentTimeMillis()}.pcm")
            try {
                recorder.startRecording()
            } catch (se: SecurityException) {
                Log.w(TAG, "RECORD_AUDIO revoked at runtime, skipping clip")
                try { recorder.release() } catch (_: Exception) {}
                return@withContext null
            }
            val totalBytes = SAMPLE_RATE * RECORD_SECONDS * 2 // 16-bit mono
            var bytesRead = 0
            val buf = ByteArray(8192)
            try {
                FileOutputStream(outFile).use { fos ->
                    while (bytesRead < totalBytes) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n > 0) {
                            fos.write(buf, 0, n)
                            bytesRead += n
                        } else break
                    }
                }
            } catch (se: SecurityException) {
                Log.w(TAG, "RECORD_AUDIO revoked mid-record, discarding clip")
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
                outFile.delete()
                return@withContext null
            }
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()

            if (!outFile.exists() || outFile.length() < 8000) {
                Log.w(TAG, "Clip too short, discarding")
                outFile.delete()
                return@withContext null
            }
            Log.d(TAG, "10s clip saved: ${outFile.length()} bytes")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "record failed: ${e.message}", e)
            null
        }
    }

    /**
     * Transcribe PCM clip. Tries Vosk file decode if model is unpacked,
     * otherwise returns empty (caller falls back to no-transcript summary).
     * Kept best-effort: never blocks SOS on failure.
     */
    suspend fun transcribeClip(context: Context, pcmFile: File?): String =
        withContext(Dispatchers.IO) {
            if (pcmFile == null || !pcmFile.exists()) return@withContext ""
            try {
                var isShared = false
                val model = com.sriox.vasateysec.VoskWakeWordService.sharedModel?.also { isShared = true } ?: run {
                    val modelDir = File(context.filesDir, "model")
                    val candidates = listOf(
                        File(modelDir, "model"),
                        modelDir,
                        File(context.getExternalFilesDir(null), "model")
                    )
                    val resolved = candidates.firstOrNull { it.exists() && it.isDirectory }
                    if (resolved != null) {
                        try { org.vosk.Model(resolved.absolutePath) } catch (_: Exception) { null }
                    } else null
                }

                if (model == null) {
                    Log.d(TAG, "Vosk model not available, skipping STT")
                    return@withContext ""
                }

                val rec = org.vosk.Recognizer(model, SAMPLE_RATE.toFloat())
                rec.setWords(true)
                val data = pcmFile.readBytes()
                var offset = 0
                val chunk = 8000
                while (offset < data.size) {
                    val end = minOf(offset + chunk, data.size)
                    val slice = data.copyOfRange(offset, end)
                    rec.acceptWaveForm(slice, slice.size / 2)
                    offset = end
                }
                val finalJson = rec.finalResult
                rec.close()
                if (!isShared) {
                    try { model.close() } catch (_: Exception) {}
                }
                val text = extractText(finalJson)
                Log.d(TAG, "STT transcript: $text")
                text.take(MAX_TRANSCRIPT_CHARS * 2)
            } catch (e: Exception) {
                Log.w(TAG, "Vosk file STT failed (non-fatal): ${e.message}")
                ""
            }
        }

    private fun extractText(json: String): String {
        return try {
            // {"text": "..."} — same parsing style as VoskWakeWordService
            json.substringAfter("\"text\"").substringAfter(":").substringAfter("\"").substringBefore("\"").trim()
        } catch (_: Exception) { "" }
    }

    /**
     * Fast extractive summarizer. Pure Kotlin, <50ms, no model.
     * Used both for guardian follow-up and AiChat fast replies.
     */
    fun summarize(transcript: String, hasLocation: Boolean = true): SituationResult {
        val clean = transcript.trim()
        if (clean.isEmpty()) {
            return SituationResult(
                transcript = "",
                summary = "No speech captured in 10s window after SOS." +
                    (if (hasLocation) " Location was shared with guardians." else " Location unavailable — check last known position.") +
                    " Treat as check-in needed.",
                urgency = "MEDIUM",
                category = "no_audio"
            )
        }
        val lower = clean.lowercase()
        val short = if (clean.length > MAX_TRANSCRIPT_CHARS) clean.take(MAX_TRANSCRIPT_CHARS) + "…" else clean

        var category = "general_distress"
        var urgency = "MEDIUM"
        var situation = "Person triggered SOS and surrounding audio was captured."
        var advice = "Share live location, try calling back, head to a lit public place."

        when {
            lower.contains("help") && (lower.contains("follow") || lower.contains("stalk") || lower.contains("behind") || lower.contains("chasing")) -> {
                category = "stalking"; urgency = "HIGH"
                situation = "Possible stalking/following incident described."
                advice = "Do NOT go home. Enter a shop, share live location, call guardian now."
            }
            lower.contains("accident") || lower.contains("crash") || lower.contains("hit") || lower.contains("bleed") || lower.contains("hurt") || lower.contains("pain") || lower.contains("hospital") -> {
                category = "medical"; urgency = "HIGH"
                situation = "Possible accident/medical emergency described."
                advice = "Call back immediately, dispatch help to shared location, keep phone line open."
            }
            lower.contains("night") || lower.contains("dark") || lower.contains("alone") || lower.contains("scared") || lower.contains("afraid") -> {
                category = "night_unsafe"; urgency = "MEDIUM"
                situation = "Person feels unsafe, possibly alone at night."
                advice = "Stay on call, stick to lit main roads, share live location."
            }
            lower.contains("thief") || lower.contains("steal") || lower.contains("rob") || lower.contains("snatch") || lower.contains("harass") || lower.contains("threat") -> {
                category = "harassment"; urgency = "HIGH"
                situation = "Possible harassment/theft/threat described."
                advice = "Move to crowded place, attract attention, alert guardians."
            }
            lower.contains("fire") -> {
                category = "fire"; urgency = "HIGH"
                situation = "Fire mentioned in audio."
                advice = "Evacuate, call fire helpline, share location."
            }
            lower.contains("kidnap") || lower.contains("force") || lower.contains("please") && lower.contains("leave") -> {
                category = "coercion"; urgency = "HIGH"
                situation = "Possible coercion/kidnap risk keywords."
                advice = "Treat as HIGH priority, attempt call + location check."
            }
        }

        val summary = "Situation ($urgency): $situation Advice: $advice Transcript: \"$short\""
        return SituationResult(transcript = short, summary = summary, urgency = urgency, category = category)
    }

    /** One-shot: record -> transcribe -> Gemma abstractive (if .task) else keyword engine. */
    suspend fun captureAndSummarize(context: Context, hasLocation: Boolean): SituationResult =
        withContext(Dispatchers.IO) {
            val clip = try { recordTenSecondClip(context) } catch (_: Exception) { null }
            val transcript = try { transcribeClip(context, clip) } catch (_: Exception) { "" }
            try { clip?.delete() } catch (_: Exception) {}

            // Try SmolLM2 real emergency summarization first (guarded by 3.5s timeout).
            if (transcript.isNotBlank()) {
                try {
                    val smolSummary = SmolLM2Helper.generateEmergencySummary(context, transcript)
                    if (!smolSummary.isNullOrBlank()) {
                        val urgency = when {
                            smolSummary.contains("HIGH", ignoreCase = true) -> "HIGH"
                            smolSummary.contains("LOW", ignoreCase = true) -> "LOW"
                            else -> "MEDIUM"
                        }
                        return@withContext SituationResult(
                            transcript = transcript.take(MAX_TRANSCRIPT_CHARS),
                            summary = smolSummary.take(180),
                            urgency = urgency,
                            category = "smollm2"
                        )
                    }
                } catch (_: Exception) { }
            }
            summarize(transcript, hasLocation)
        }


    /** Compact format for SMS payload (<=160 chars so it fits standard SMS with location link). */
    fun toSmsBody(result: SituationResult): String {
        val core = if (result.category == "smollm2") {
            result.summary
        } else {
            "[${result.urgency}] ${result.summary}"
        }
        return if (core.length > 160) core.take(160) + "…" else core
    }
}
