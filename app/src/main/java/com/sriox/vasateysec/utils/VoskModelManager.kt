package com.sriox.vasateysec.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * On-demand Telugu Vosk model (~40 MB zip → ~80 MB unpacked).
 * English model stays bundled in assets; Telugu downloads once into
 * filesDir/model_te and is reused offline afterwards.
 */
object VoskModelManager {

    private const val TAG = "VoskModelManager"

    const val TE_DIR_NAME = "model_te"
    const val TE_URL = "https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip"

    fun teDir(context: Context): File = File(context.filesDir, TE_DIR_NAME)

    /** Ready when the unpacked acoustic model file is present. */
    fun isTeluguReady(context: Context): Boolean {
        return try {
            val dir = teDir(context)
            dir.isDirectory &&
                File(dir, "am/final.mdl").exists() &&
                File(dir, "graph").exists()
        } catch (_: Exception) { false }
    }

    sealed interface TeProgress {
        data class Download(val doneMb: Long, val totalMb: Long) : TeProgress
        object Unzipping : TeProgress
    }

    /**
     * Downloads + unzips the Telugu model. Returns true when usable.
     * Safe to call twice; partial/corrupt data is wiped first.
     */
    suspend fun downloadTeluguModel(
        context: Context,
        onProgress: (TeProgress) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isTeluguReady(context)) return@withContext true

            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.MINUTES)
                .build()
            val zipFile = File(context.cacheDir, "vosk-model-small-te.zip")
            if (zipFile.exists()) zipFile.delete()

            val request = Request.Builder()
                .url(TE_URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Telugu model HTTP ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val total = body.contentLength()
                var done = 0L
                var lastEmit = 0L
                body.byteStream().use { input ->
                    FileOutputStream(zipFile).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastEmit > 512 * 1024) {
                                lastEmit = done
                                onProgress(TeProgress.Download(done / (1024 * 1024), if (total > 0) total / (1024 * 1024) else -1))
                            }
                        }
                    }
                }
            }
            if (!zipFile.exists() || zipFile.length() < 10_000_000L) {
                Log.w(TAG, "Telugu zip too small: ${zipFile.length()}")
                zipFile.delete()
                return@withContext false
            }

            onProgress(TeProgress.Unzipping)
            val dir = teDir(context)
            if (dir.exists()) dir.deleteRecursively()
            unzipToCacheStripped(zipFile, context)
            zipFile.delete()
            val ok = isTeluguReady(context)
            Log.d(TAG, "Telugu model ready=$ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Telugu model download failed: ${e.message}")
            try { teDir(context).deleteRecursively() } catch (_: Exception) {}
            false
        }
    }

    /**
     * The zip contains a top-level folder (vosk-model-small-te-0.42/…);
     * strip it so am/, graph/, ivector/ land directly in model_te/.
     */
    private fun unzipToCacheStripped(zipFile: File, context: Context) {
        val destDir = teDir(context).canonicalFile
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val entry = zin.nextEntry ?: break
                val stripped = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (stripped.isEmpty() || entry.isDirectory) {
                    zin.closeEntry()
                    continue
                }
                val outFile = File(destDir, stripped).canonicalFile
                if (!outFile.path.startsWith(destDir.path)) {
                    zin.closeEntry()
                    continue
                }
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out ->
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                zin.closeEntry()
            }
        }
    }
}
