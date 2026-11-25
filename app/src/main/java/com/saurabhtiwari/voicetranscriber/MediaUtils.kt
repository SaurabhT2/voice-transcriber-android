package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.net.Uri
import java.util.Locale

internal fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

internal fun resolveContentType(context: Context, uri: Uri, name: String): String {
    val reported = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT).orEmpty()
    if (reported.startsWith("audio/")) return reported
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (extension) {
        "mp3" -> "audio/mpeg"
        "mp4", "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "3gp" -> "audio/3gpp"
        else -> "application/octet-stream"
    }
}
