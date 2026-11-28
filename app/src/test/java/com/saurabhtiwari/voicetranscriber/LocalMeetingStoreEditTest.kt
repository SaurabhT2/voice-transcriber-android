package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalMeetingStoreEditTest {
    private val dir: File = Files.createTempDirectory("meetings").toFile()
    private val store = LocalMeetingStore(dir)
    private fun save(owner: String = "u1", title: String = "Note") =
        store.save(owner, title, "original text", "2026-11-01T10:00:00Z", "2026-11-01T10:05:00Z", "en")

    @Test
    fun renamePersistsAndRejectsBlankTitles() {
        val record = save()
        assertTrue(store.rename("u1", record.eventId, "  Budget call  "))
        assertEquals("Budget call", LocalMeetingStore(dir).find("u1", record.eventId)?.title)
        assertFalse(store.rename("u1", record.eventId, "   "))
        assertEquals("Budget call", store.find("u1", record.eventId)?.title)
        assertFalse(store.rename("u1", "missing", "x"))
    }

    @Test
    fun otherOwnersCannotEditOrDelete() {
        val record = save(owner = "u1")
        assertFalse(store.rename("u2", record.eventId, "hijack"))
        assertFalse(store.updateTranscript("u2", record.eventId, "hijack"))
        assertFalse(store.delete("u2", record.eventId))
        assertEquals("Note", store.find("u1", record.eventId)?.title)
    }

    @Test
    fun transcriptEditsApplyOnlyBeforeSync() {
        val record = save()
        assertTrue(store.updateTranscript("u1", record.eventId, "corrected text"))
        assertEquals("corrected text", LocalMeetingStore(dir).find("u1", record.eventId)?.transcript)
        assertEquals("corrected text", store.pendingSync("u1").single().transcript)
        assertFalse(store.updateTranscript("u1", record.eventId, "   "))

        store.markSynced("u1", record.eventId, "meeting-1")
        assertFalse(store.updateTranscript("u1", record.eventId, "too late"))
        assertEquals("corrected text", store.find("u1", record.eventId)?.transcript)
    }

    @Test
    fun deleteRemovesTheFileAndTheListing() {
        val keep = save(title = "Keep")
        val gone = save(title = "Gone")
        assertTrue(File(dir, gone.eventId + ".json").exists())
        assertTrue(store.delete("u1", gone.eventId))
        assertFalse(File(dir, gone.eventId + ".json").exists())
        assertEquals(listOf(keep.eventId), LocalMeetingStore(dir).list("u1").map { it.eventId })
        assertFalse(store.delete("u1", gone.eventId))
    }

    @Test
    fun deleteByMeetingIdReturnsWhatItRemoved() {
        val synced = save(title = "Synced")
        val pending = save(title = "Pending")
        store.markSynced("u1", synced.eventId, "meeting-9")
        val removed = store.deleteByMeetingId("u1", "meeting-9")
        assertEquals(listOf(synced.eventId), removed.map { it.eventId })
        assertNull(store.find("u1", synced.eventId))
        assertEquals(pending.eventId, store.find("u1", pending.eventId)?.eventId)
        assertTrue(store.deleteByMeetingId("u1", "meeting-9").isEmpty())
    }
}
