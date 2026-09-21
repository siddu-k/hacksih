package com.sriox.vasateysec.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * High-Performance Offline SmolLM2-360M Helper
 * Manages detection, background warmup, and local execution of SmolLM2-360M-Instruct GGUF (~229 MB).
 * Optimized for mobile CPU speed, low RAM footprint (~350MB), and instant emergency SMS summarization.
 */
object SmolLM2Helper {

    private const val TAG = "SmolLM2Helper"
    
    // SmolLM2-360M-Instruct Q4_K_M GGUF (Single file ~258 MB from bartowski)
    const val MODEL_FILE_NAME = "SmolLM2-360M-Instruct-Q4_K_M.gguf"
    const val DOWNLOAD_URL = "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf"
    const val ESTIMATED_SIZE_MB = 258

    // Optional HuggingFace token
    const val HARDCODED_HF_TOKEN: String = ""

    fun getModelFile(context: Context): File {
        val primary = File(context.getExternalFilesDir(null), MODEL_FILE_NAME)
        if (primary.exists()) return primary
        val alt = File(context.getExternalFilesDir(null), "smollm2-360m-instruct-q4_k_m.gguf")
        if (alt.exists()) return alt
        return primary
    }

    fun isModelDownloaded(context: Context): Boolean {
        val file = getModelFile(context)
        // Ensure file exists and is at least 180 MB
        return file.exists() && file.length() > 180_000_000L
    }

    /**
     * Warms up the model into memory in the background.
     */
    suspend fun warmUpModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        val modelFile = getModelFile(context)
        if (!isModelDownloaded(context)) return@withContext false
        try {
            LlamaBridge.initModel(modelFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to warm up model: ${e.message}", e)
            false
        }
    }

    /**
     * Executes local SmolLM2 inference for interactive chat in AiChatActivity.
     */
    suspend fun generateChatResponse(context: Context, userPrompt: String): String = withContext(Dispatchers.IO) {
        val modelFile = getModelFile(context)
        if (!isModelDownloaded(context)) {
            return@withContext "SmolLM2 360M is not downloaded. Go to Settings and download it (~229 MB) for offline AI chat."
        }

        try {
            // ChatML template - general purpose helpful AI assistant
            val formattedPrompt = "<|im_start|>system\nYou are SahAi, a helpful, knowledgeable, and friendly AI assistant. Answer whatever question the user asks accurately, clearly, and concisely.<|im_end|>\n<|im_start|>user\n${userPrompt.trim()}<|im_end|>\n<|im_start|>assistant\n"
            
            val result = LlamaBridge.generateResponse(modelFile.absolutePath, formattedPrompt, maxTokens = 256)
            if (!result.isNullOrBlank() && !result.startsWith("Error:")) {
                result.trim()
            } else {
                "SahAi: I am ready to help. What would you like to know?"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat inference error: ${e.message}", e)
            "SahAi: Processing prompt locally. Please ask again."
        }
    }

    /**
     * Legacy adapter function for backward compatibility.
     */
    suspend fun generateResponse(context: Context, prompt: String): String {
        return generateChatResponse(context, prompt)
    }

    /**
     * Fast emergency SMS situation summarizer for post-wake-word 10s audio.
     * Uses a strict 3.5s timeout to guarantee zero delay for SOS delivery.
     * Returns null on timeout/error so the instant rule engine takes over immediately.
     */
    suspend fun generateEmergencySummary(context: Context, transcript: String): String? = withContext(Dispatchers.IO) {
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isEmpty() || !isModelDownloaded(context)) {
            return@withContext null
        }

        val modelFile = getModelFile(context)
        withTimeoutOrNull(3500L) {
            try {
                // Highly constrained prompt asking for exactly 1 sentence under 120 chars
                val prompt = "<|im_start|>system\nYou are an emergency dispatch AI. Summarize the 10-second distress audio transcript in ONE sentence under 120 characters for an emergency SMS. Format: [URGENCY HIGH/MED/LOW] situation and action.<|im_end|>\n<|im_start|>user\nTranscript: \"${cleanTranscript.take(400)}\"<|im_end|>\n<|im_start|>assistant\n"
                
                val raw = LlamaBridge.generateResponse(modelFile.absolutePath, prompt, maxTokens = 45)
                if (!raw.isNullOrBlank() && !raw.startsWith("Error:")) {
                    var cleaned = raw.trim().replace("\n", " ")
                    if (cleaned.length > 150) {
                        cleaned = cleaned.take(150) + "…"
                    }
                    cleaned
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Emergency summary timeout/error: ${e.message}")
                null
            }
        }
    }
}
