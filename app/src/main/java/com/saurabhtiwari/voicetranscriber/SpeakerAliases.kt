package com.saurabhtiwari.voicetranscriber

import org.json.JSONObject
import java.io.File

/**
 * Applies user-chosen speaker names. Aliases are display overrides: the server's labels are never
 * changed, so a rename on this phone does not alter what the web app shows.
 */
fun SpeakerTranscript.withAliases(aliases: Map<String, String>): SpeakerTranscript {
    if (aliases.isEmpty()) return this
    fun nameFor(speakerId: String?, fallback: String?): String? =
        speakerId?.let { aliases[it]?.trim()?.takeIf { name -> name.isNotEmpty() } } ?: fallback
    return SpeakerTranscript(
        turns = turns.map { it.copy(label = nameFor(it.speakerId, it.label)) },
        speakers = speakers.map { it.copy(label = nameFor(it.speakerId, it.label) ?: it.label) }
    )
}

/**
 * Speaker names chosen on this device, per meeting (`meeting:<id>` or `event:<id>` keys).
 * One small JSON file, written atomically; all methods do blocking I/O.
 */
class SpeakerAliasStore(private val file: File) {
    private var cache: MutableMap<String, MutableMap<String, String>>? = null

    @Synchronized
    fun get(key: String): Map<String, String> = load()[key]?.toMap().orEmpty()

    /** Sets or, when [name] is blank, removes one speaker's name. */
    @Synchronized
    fun set(key: String, speakerId: String, name: String) {
        val all = load()
        val clean = name.trim().take(MAX_NAME_LENGTH)
        val forMeeting = all.getOrPut(key) { LinkedHashMap() }
        if (clean.isEmpty()) forMeeting.remove(speakerId) else forMeeting[speakerId] = clean
        if (forMeeting.isEmpty()) all.remove(key)
        save(all)
    }

    @Synchronized
    fun clear(key: String) {
        val all = load()
        if (all.remove(key) != null) save(all)
    }

    private fun load(): MutableMap<String, MutableMap<String, String>> {
        cache?.let { return it }
        val loaded = LinkedHashMap<String, MutableMap<String, String>>()
        if (file.isFile) {
            try {
                val root = JSONObject(file.readText())
                for (key in root.keys()) {
                    val names = root.optJSONObject(key) ?: continue
                    val map = LinkedHashMap<String, String>()
                    for (speaker in names.keys()) names.optString(speaker).takeIf { it.isNotBlank() }?.let { map[speaker] = it }
                    if (map.isNotEmpty()) loaded[key] = map
                }
            } catch (_: Exception) {
                file.renameTo(File(file.parentFile, file.name + ".corrupt"))
            }
        }
        cache = loaded
        return loaded
    }

    private fun save(all: Map<String, Map<String, String>>) {
        val root = JSONObject()
        all.forEach { (key, names) -> root.put(key, JSONObject(names)) }
        writeTextAtomically(file, root.toString())
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}
