package com.sriox.vasateysec.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uploads emergency evidence photos to Supabase Storage (`emergency-photos`
 * bucket, public-read) and returns public URLs.
 *
 * REST API via OkHttp — no extra SDK dependency.
 * Never throws: every failure returns null so the SOS flow always
 * falls back to SMS-without-links instead of blocking the alert.
 */
object SupabasePhotoUploader {

    private const val TAG = "SupaPhotoUpload"
    private const val UPLOAD_TIMEOUT_MS = 30_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun publicUrl(remotePath: String): String =
        "${SupabaseConfig.URL}/storage/v1/object/public/${SupabaseConfig.PHOTO_BUCKET}/$remotePath"

    /**
     * PUT one JPEG to Storage. Returns the public URL on success, null otherwise.
     */
    suspend fun uploadPhoto(file: File?, remotePath: String): String? = withContext(Dispatchers.IO) {
        if (file == null || !file.exists() || file.length() == 0L) return@withContext null
        try {
            withTimeoutOrNull(UPLOAD_TIMEOUT_MS) {
                val url = "${SupabaseConfig.URL}/storage/v1/object/${SupabaseConfig.PHOTO_BUCKET}/$remotePath"
                val body = file.asRequestBody("image/jpeg".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .put(body)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
                    .addHeader("Content-Type", "image/jpeg")
                    .addHeader("x-upsert", "true")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val public = publicUrl(remotePath)
                        Log.d(TAG, "✅ Uploaded ${file.name} (${file.length()}B) → $public")
                        public
                    } else {
                        val errBody = try { resp.body?.string()?.take(200) } catch (_: Exception) { "?" }
                        Log.w(TAG, "Upload failed ${resp.code} for $remotePath: $errBody — check bucket '${SupabaseConfig.PHOTO_BUCKET}' exists + public")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Upload exception for $remotePath (non-fatal): ${e.message}")
            null
        }
    }

    /**
     * Uploads front+back in parallel. Returns Pair(frontUrl, backUrl), either may be null.
     * Short remote paths keep the SMS payload compact.
     */
    suspend fun uploadEmergencyPhotos(
        frontPhoto: File?,
        backPhoto: File?,
        userId: String,
        alertId: String
    ): Pair<String?, String?> = supervisorScope {
        if (frontPhoto == null && backPhoto == null) return@supervisorScope Pair(null, null)
        val safeUser = userId.replace(Regex("[^A-Za-z0-9_-]"), "").take(16).ifBlank { "user" }
        val shortId = alertId.replace("-", "").take(8)
        val frontJob = async { uploadPhoto(frontPhoto, "sos_${safeUser}_${shortId}/f.jpg") }
        val backJob = async { uploadPhoto(backPhoto, "sos_${safeUser}_${shortId}/b.jpg") }
        try {
            Pair(frontJob.await(), backJob.await())
        } catch (e: Exception) {
            Log.w(TAG, "Parallel upload failed (non-fatal): ${e.message}")
            Pair(null, null)
        }
    }
}
