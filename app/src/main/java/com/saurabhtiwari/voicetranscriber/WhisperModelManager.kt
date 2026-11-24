package com.saurabhtiwari.voicetranscriber

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object WhisperModelManager {
    const val MODEL_FILE_NAME = "ggml-tiny-q5_1.bin"

    /** Identifier recorded with synced transcripts; always derived from the file name. */
    val MODEL_ID: String = MODEL_FILE_NAME.removeSuffix(".bin")

    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_FILE_NAME"
    private const val MODEL_SHA256 =
        "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    /**
     * Downloads (and verifies) the model. An interrupted download is kept as a `.part` file and
     * resumed with an HTTP Range request next time; the file is only accepted if its SHA-256 matches.
     */
    suspend fun download(
        context: Context,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val destination = modelFile(context)
        if (destination.exists() && isValidModel(destination)) {
            return@withContext destination
        }

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "$MODEL_FILE_NAME.part")

        var existing = if (temporary.isFile) temporary.length() else 0L
        if (existing > 0 && isValidModel(temporary)) {
            return@withContext finalize(temporary, destination)
        }

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            val append: Boolean
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> append = false // fresh download (or server ignored Range)
                HttpURLConnection.HTTP_PARTIAL -> append = true
                416 -> {
                    temporary.delete() // stale partial file; next attempt starts clean
                    throw IOException("The partial download was out of date. Please try again.")
                }
                else -> throw IOException("Model download failed with HTTP ${connection.responseCode}")
            }
            if (!append) existing = 0L

            val remaining = connection.contentLengthLong
            val totalBytes = if (remaining > 0) existing + remaining else -1L
            var downloadedBytes = existing
            val coroutine = currentCoroutineContext()

            connection.inputStream.buffered().use { input ->
                FileOutputStream(temporary, append).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                throw IOException("The model download was interrupted. Tap again to resume.")
            }
            if (!isValidModel(temporary)) {
                temporary.delete() // corrupt data cannot be resumed
                throw IOException("Downloaded model failed SHA-256 verification")
            }
            finalize(temporary, destination)
        } finally {
            connection.disconnect()
        }
    }

    private fun finalize(temporary: File, destination: File): File {
        if (destination.exists()) destination.delete()
        if (!temporary.renameTo(destination)) {
            throw IOException("Unable to finalize downloaded model")
        }
        return destination
    }

    private fun isValidModel(file: File): Boolean {
        if (!file.isFile || file.length() < 1_000_000L) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return actual.equals(MODEL_SHA256, ignoreCase = true)
    }
}
