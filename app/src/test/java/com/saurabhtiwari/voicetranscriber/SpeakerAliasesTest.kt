package com.saurabhtiwari.voicetranscriber

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpeakerAliasesTest {
    private fun tempFile(): File = File(Files.createTempDirectory("aliases").toFile(), "aliases.json")

    private fun seg(start: Double, text: String, speaker: String?) = JSONObject()
        .put("start", start).put("end", start + 2).put("text", text).put("speaker_id", speaker ?: JSONObject.NULL)

    private val speakers = JSONArray()
        .put(JSONObject().put("speaker_id", "speaker_1").put("label", "Speaker 1").put("speaking_seconds", 4))
        .put(JSONObject().put("speaker_id", "speaker_2").put("label", "Speaker 2").put("speaking_seconds", 2))
    private val segments = JSONArray().put(seg(0.0, "a", "speaker_1")).put(seg(2.0, "b", "speaker_2")).put(seg(4.0, "c", "speaker_1"))

    @Test
    fun aliasesRenameTurnsAndSpeakerList() {
        val result = DisplayTranscripts.build(segments, speakers, mapOf("speaker_1" to "Asha", "speaker_9" to "Nobody"))
        assertEquals(listOf("Asha", "Speaker 2", "Asha"), result.turns.map { it.label })
        assertEquals(listOf("Asha", "Speaker 2"), result.speakers.map { it.label })
        assertEquals(listOf("speaker_1", "speaker_2"), result.speakers.map { it.speakerId })
    }

    @Test
    fun blankAliasKeepsTheServerLabel() {
        val result = DisplayTranscripts.build(segments, speakers, mapOf("speaker_1" to "   "))
        assertEquals("Speaker 1", result.speakers.first().label)
    }

    @Test
    fun withoutSpeakersEveryNonBlankSegmentIsItsOwnLine() {
        val plain = JSONArray().put(seg(0.0, "one", null)).put(seg(3.0, "two", null)).put(seg(6.0, "  ", null))
        val result = DisplayTranscripts.build(plain, JSONArray(), mapOf("speaker_1" to "Asha"))
        assertFalse(result.hasSpeakers)
        assertEquals(listOf("one", "two"), result.turns.map { it.text })
        assertEquals(listOf(0.0, 3.0), result.turns.map { it.startSeconds })
        assertTrue(DisplayTranscripts.build(JSONArray(), JSONArray(), emptyMap()).isEmpty)
    }

    @Test
    fun storePersistsAcrossInstances() {
        val file = tempFile()
        SpeakerAliasStore(file).set("meeting:m1", "speaker_1", "  Asha  ")
        SpeakerAliasStore(file).set("meeting:m1", "speaker_2", "Ben")
        val reloaded = SpeakerAliasStore(file)
        assertEquals(mapOf("speaker_1" to "Asha", "speaker_2" to "Ben"), reloaded.get("meeting:m1"))
        assertTrue(reloaded.get("meeting:other").isEmpty())
    }

    @Test
    fun blankNameRemovesAndEmptyMeetingsDisappear() {
        val store = SpeakerAliasStore(tempFile())
        store.set("k", "speaker_1", "Asha")
        store.set("k", "speaker_1", "")
        assertTrue(store.get("k").isEmpty())
        store.set("k", "speaker_1", "Asha")
        store.clear("k")
        assertTrue(store.get("k").isEmpty())
    }

    @Test
    fun namesAreTruncated() {
        val store = SpeakerAliasStore(tempFile())
        store.set("k", "speaker_1", "x".repeat(500))
        assertEquals(SpeakerAliasStore.MAX_NAME_LENGTH, store.get("k").getValue("speaker_1").length)
    }

    @Test
    fun corruptFileIsSetAsideNotFatal() {
        val file = tempFile()
        file.writeText("{not json")
        val store = SpeakerAliasStore(file)
        assertTrue(store.get("k").isEmpty())
        assertTrue(File(file.parentFile, "aliases.json.corrupt").exists())
        store.set("k", "speaker_1", "Asha")
        assertEquals("Asha", SpeakerAliasStore(file).get("k")["speaker_1"])
    }
}
