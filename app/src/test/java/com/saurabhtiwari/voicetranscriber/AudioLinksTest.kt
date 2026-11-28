package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudioLinksTest {
    private val root: File = Files.createTempDirectory("audio").toFile()
    private val recordings = File(root, "recordings").apply { mkdirs() }
    private val linkFile = File(root, "audio_links.json")
    private fun audio(name: String, bytes: Int = 10): File = File(recordings, name).apply { writeBytes(ByteArray(bytes) { 1 }) }

    @Test
    fun linkSurvivesRestartAndResolvesToTheFile() {
        val file = audio("meeting_1.m4a")
        AudioLinkStore(linkFile).link(AudioKeys.event("e1"), "meeting_1.m4a")
        val store = AudioLinkStore(linkFile)
        assertEquals("meeting_1.m4a", store.find(AudioKeys.event("e1")))
        assertEquals(file, store.resolve(recordings, listOf(AudioKeys.meeting("m1"), AudioKeys.event("e1"))))
    }

    @Test
    fun missingOrEmptyAudioDoesNotResolve() {
        val store = AudioLinkStore(linkFile)
        store.link("k", "gone.m4a")
        assertNull(store.resolve(recordings, listOf("k")))
        audio("empty.m4a", bytes = 0)
        store.link("k2", "empty.m4a")
        assertNull(store.resolve(recordings, listOf("k2")))
    }

    @Test
    fun linksCannotEscapeTheRecordingsFolder() {
        val store = AudioLinkStore(linkFile)
        File(root, "secret.m4a").writeBytes(ByteArray(5))
        store.link("k", "../secret.m4a")
        store.link("k2", "/etc/passwd")
        store.link("k3", "")
        assertNull(store.find("k")); assertNull(store.find("k2")); assertNull(store.find("k3"))
        // a tampered links file is filtered on load too
        linkFile.writeText("""{"k":"../secret.m4a","ok":"meeting_2.m4a"}""")
        val reloaded = AudioLinkStore(linkFile)
        assertNull(reloaded.find("k"))
        assertEquals("meeting_2.m4a", reloaded.find("ok"))
        assertNull(reloaded.resolve(recordings, emptyList(), titleHint = "../secret.m4a"))
    }

    @Test
    fun titleHintCoversRecordingsTranscribedBeforeLinksExisted() {
        val file = audio("meeting_3.m4a")
        assertEquals(file, AudioLinkStore(linkFile).resolve(recordings, listOf("event:unknown"), titleHint = "meeting_3.m4a"))
        assertNull(AudioLinkStore(linkFile).resolve(recordings, emptyList(), titleHint = "Renamed meeting"))
    }

    @Test
    fun unlinkRemovesOneKeyOrEveryKeyForAFile() {
        val store = AudioLinkStore(linkFile)
        store.link("a", "x.m4a"); store.link("b", "x.m4a"); store.link("c", "y.m4a")
        store.unlink("a")
        assertNull(store.find("a")); assertEquals("x.m4a", store.find("b"))
        store.unlinkFile("x.m4a")
        assertNull(AudioLinkStore(linkFile).find("b"))
        assertEquals("y.m4a", AudioLinkStore(linkFile).find("c"))
    }

    @Test
    fun corruptLinkFileIsSetAside() {
        linkFile.writeText("garbage")
        assertNull(AudioLinkStore(linkFile).find("k"))
        assertTrue(File(root, "audio_links.json.corrupt").exists())
    }

    @Test
    fun orphanedAudioIsReportedButSharedAudioIsKept() {
        val store = AudioLinkStore(linkFile)
        store.link("event:e1", "shared.m4a")      // same recording transcribed twice: locally and on the GPU
        store.link("meeting:m1", "shared.m4a")
        store.link("meeting:m2", "solo.m4a")

        assertEquals(emptyList<String>(), store.unlinkAndCollectOrphans(listOf("event:e1")))      // still used by m1
        assertEquals(listOf("shared.m4a"), store.unlinkAndCollectOrphans(listOf("meeting:m1")))   // last link gone
        assertEquals(listOf("solo.m4a"), AudioLinkStore(linkFile).unlinkAndCollectOrphans(listOf("meeting:m2")))
        assertNull(AudioLinkStore(linkFile).find("meeting:m2"))
    }

    @Test
    fun legacyRecordingFoundByTitleIsCollectedUnlessLinkedElsewhere() {
        val store = AudioLinkStore(linkFile)
        assertEquals(listOf("meeting_5.m4a"), store.unlinkAndCollectOrphans(listOf("event:none"), "meeting_5.m4a"))
        store.link("meeting:m9", "meeting_6.m4a")
        assertEquals(emptyList<String>(), store.unlinkAndCollectOrphans(listOf("event:none"), "meeting_6.m4a"))
        assertEquals(emptyList<String>(), store.unlinkAndCollectOrphans(emptyList(), "../escape.m4a"))
    }
}
