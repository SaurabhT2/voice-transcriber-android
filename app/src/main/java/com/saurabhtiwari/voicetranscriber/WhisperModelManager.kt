package com.saurabhtiwari.voicetranscriber

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object WhisperModelManager {
    const val MODEL_FILE_NAME = "ggml-tiny-q5_1.bin"
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_FILE_NAME"
    private const val MODEL_SHA256 =
        "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_FILE_NAME)

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
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }

        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Model download failed with HTTP ${connection.responseCode}")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                throw IOException("Downloaded model is incomplete")
            }
            if (!isValidModel(temporary)) {
                throw IOException("Downloaded model failed SHA-256 verification")
            }

            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                throw IOException("Unable to finalize downloaded model")
            }
            destination
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
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
