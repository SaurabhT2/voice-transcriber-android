package com.saurabhtiwari.voicetranscriber

import org.json.JSONObject
import java.io.File

/** Keys under which a recording's audio can be linked. */
object AudioKeys {
    fun event(eventId: String) = "event:$eventId"
    fun meeting(meetingId: String) = "meeting:$meetingId"
    fun job(jobId: String) = "job:$jobId"
}

/**
 * Remembers which recorded audio file on this phone belongs to which transcript, so the transcript
 * can be played back. Only the file *name* is stored, and only plain names are accepted, so a link
 * can never point outside the recordings folder.
 */
class AudioLinkStore(private val file: File) {
    private var cache: MutableMap<String, String>? = null

    @Synchronized
    fun link(key: String, audioFileName: String) {
        if (!isPlainName(audioFileName)) return
        val all = load()
        if (all[key] == audioFileName) return
        all[key] = audioFileName
        save(all)
    }

    @Synchronized
    fun find(key: String): String? = load()[key]

    @Synchronized
    fun unlink(key: String) {
        val all = load()
        if (all.remove(key) != null) save(all)
    }

    /** Drops every link to [audioFileName], e.g. after the audio file was deleted. */
    @Synchronized
    fun unlinkFile(audioFileName: String) {
        val all = load()
        if (all.values.removeAll { it == audioFileName }) save(all)
    }

    /**
     * Unlinks [keys] and returns the audio file names that nothing links to any more, so they can be
     * deleted with the transcript they belonged to. A file still linked from another transcript
     * (the same recording transcribed twice) is kept. [titleHint] names a pre-link recording.
     */
    @Synchronized
    fun unlinkAndCollectOrphans(keys: List<String>, titleHint: String? = null): List<String> {
        val all = load()
        val candidates = keys.mapNotNull { all[it] } + listOfNotNull(titleHint?.takeIf { isPlainName(it) })
        keys.forEach { all.remove(it) }
        save(all)
        return candidates.distinct().filter { name -> all.values.none { it == name } }
    }

    /**
     * The audio file on this device for a transcript, or null if there is none (imported audio, a
     * recording made on another device, or a deleted file). [keys] are tried in order; [titleHint]
     * covers recordings transcribed before links existed, whose title is the recording's file name.
     */
    @Synchronized
    fun resolve(recordingsDir: File, keys: List<String>, titleHint: String? = null): File? {
        val candidates = keys.mapNotNull { load()[it] } + listOfNotNull(titleHint)
        for (name in candidates) {
            if (!isPlainName(name)) continue
            val candidate = File(recordingsDir, name)
            if (candidate.isFile && candidate.length() > 0L) return candidate
        }
        return null
    }

    private fun isPlainName(name: String) =
        name.isNotBlank() && name == File(name).name && name != "." && name != ".."

    private fun load(): MutableMap<String, String> {
        cache?.let { return it }
        val loaded = LinkedHashMap<String, String>()
        if (file.isFile) {
            try {
                val root = JSONObject(file.readText())
                for (key in root.keys()) root.optString(key).takeIf { isPlainName(it) }?.let { loaded[key] = it }
            } catch (_: Exception) {
                file.renameTo(File(file.parentFile, file.name + ".corrupt"))
            }
        }
        cache = loaded
        return loaded
    }

    private fun save(all: Map<String, String>) = writeTextAtomically(file, JSONObject(all).toString())
}
