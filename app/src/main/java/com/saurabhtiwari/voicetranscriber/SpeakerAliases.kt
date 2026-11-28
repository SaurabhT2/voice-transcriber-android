package com.saurabhtiwari.voicetranscriber

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Applies user-chosen speaker names. The display_name field is an app-owned override that
 * preserves the diarisation provider's original label for reset and compatibility with other clients.
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
 * Applies a speaker display-name override to the server's speakers JSON without changing provider
 * labels or timing metadata. A blank name removes the override and restores the original label.
 */
object SpeakerLabelOverrides {
    fun apply(speakers: JSONArray, speakerId: String, name: String): JSONArray {
        val clean = name.trim().take(SpeakerAliasStore.MAX_NAME_LENGTH)
        val result = JSONArray()
        var found = false
        for (index in 0 until speakers.length()) {
            val original = speakers.optJSONObject(index) ?: continue
            val row = JSONObject(original.toString())
            if (row.optString("speaker_id") == speakerId) {
                found = true
                if (clean.isEmpty()) row.remove("display_name") else row.put("display_name", clean)
            }
            result.put(row)
        }
        if (!found && clean.isNotEmpty()) {
            result.put(JSONObject().put("speaker_id", speakerId).put("display_name", clean))
        }
        return result
    }
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
