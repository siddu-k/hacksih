package com.sriox.vasateysec.utils

import android.util.Log

/**
 * JNI Native Bridge to llama.cpp C++ Engine.
 * Supports persistent in-memory model caching, fast inference, and memory management.
 */
object LlamaBridge {

    private const val TAG = "LlamaBridge"
    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("llama-bridge")
            isLibraryLoaded = true
            Log.d(TAG, "Native libllama-bridge.so loaded successfully!")
        } catch (e: UnsatisfiedLinkError) {
            isLibraryLoaded = false
            Log.e(TAG, "Native library libllama-bridge.so not found or NDK build pending: ${e.message}")
        }
    }

    /**
     * Pre-loads the model into memory asynchronously.
     */
    fun initModel(modelPath: String): Boolean {
        if (!isLibraryLoaded) {
            Log.e(TAG, "Cannot init model: libllama-bridge.so is not loaded.")
            return false
        }
        return try {
            nativeInitModel(modelPath)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing model natively: ${e.message}", e)
            false
        }
    }

    /**
     * Checks if the model is currently resident in RAM.
     */
    fun isModelLoaded(): Boolean {
        if (!isLibraryLoaded) return false
        return try {
            nativeIsModelLoaded()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Explicitly frees the model from RAM when low on memory.
     */
    fun unloadModel() {
        if (!isLibraryLoaded) return
        try {
            nativeUnloadModel()
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model: ${e.message}", e)
        }
    }

    /**
     * Calls native C++ engine to run inference on the GGUF model.
     * Uses persistent in-memory caching to avoid repeated disk reads.
     */
    fun generateResponse(modelPath: String, prompt: String, maxTokens: Int = 128): String? {
        if (!isLibraryLoaded) {
            Log.e(TAG, "Cannot run inference: libllama-bridge.so is not compiled.")
            return null
        }
        return try {
            nativeGenerateResponseWithTokens(modelPath, prompt, maxTokens)
        } catch (e: Exception) {
            Log.e(TAG, "Error during native inference execution: ${e.message}", e)
            null
        }
    }

    private external fun nativeInitModel(modelPath: String): Boolean
    private external fun nativeUnloadModel()
    private external fun nativeIsModelLoaded(): Boolean
    private external fun nativeGenerateResponse(modelPath: String, prompt: String): String
    private external fun nativeGenerateResponseWithTokens(modelPath: String, prompt: String, maxTokens: Int): String
}
