package com.saurabhtiwari.voicetranscriber

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [text] so readers only ever see the old complete file or the new complete file.
 * A crash or power loss mid-write leaves the previous version intact.
 */
internal fun writeTextAtomically(target: File, text: String) {
    val parent = target.absoluteFile.parentFile
        ?: throw IOException("No parent directory for " + target.path)
    parent.mkdirs()
    val temp = File(parent, target.name + ".tmp")
    FileOutputStream(temp).use { stream ->
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.flush()
        stream.fd.sync()
    }
    try {
        Files.move(
            temp.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
