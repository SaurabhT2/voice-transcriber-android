package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files

class CoreLogicTest {
    @Test
    fun pkceMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun pkceVerifiersAreValidAndUnique() {
        val a = Pkce.generate()
        val b = Pkce.generate()
        assertTrue(a.verifier.length in 43..128)
        assertTrue(a.verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(a.verifier, b.verifier)
        assertEquals(Pkce.challengeFor(a.verifier), a.challenge)
    }

    @Test
    fun recordingElapsedTimeAcrossPauseResume() {
        var s = RecordingState(active = true, resumedAtMs = 1_000)
        assertEquals(500L, s.elapsedAt(1_500))
        // pause at 3_000
        s = s.copy(paused = true, accumulatedMs = s.accumulatedMs + (3_000 - s.resumedAtMs))
        assertEquals(2_000L, s.elapsedAt(9_999))
        // resume at 10_000
        s = s.copy(paused = false, resumedAtMs = 10_000)
        assertEquals(2_250L, s.elapsedAt(10_250))
        // stale clock reading never goes negative
        assertEquals(2_000L, s.elapsedAt(9_000))
        assertEquals(0L, RecordingState().elapsedAt(123))
    }

    @Test
    fun failureClassification() {
        assertEquals(FailureKind.RETRYABLE, classifyFailure(SocketTimeoutException()))
        assertEquals(FailureKind.RETRYABLE, classifyFailure(IOException("offline")))
        assertEquals(FailureKind.RETRYABLE, classifyFailure(HttpException(503, "x")))
        assertEquals(FailureKind.RETRYABLE, classifyFailure(HttpException(429, "x")))
        assertEquals(FailureKind.RETRYABLE, classifyFailure(HttpException(401, "x")))
        assertEquals(FailureKind.PERMANENT, classifyFailure(HttpException(422, "x")))
        assertEquals(FailureKind.PERMANENT, classifyFailure(SessionExpiredException()))
        assertEquals(FailureKind.PERMANENT, classifyFailure(IllegalStateException()))
    }

    @Test
    fun pollBackoffGrowsAndCaps() {
        val b = PollBackoff(initialMs = 1_000, maxMs = 5_000, factor = 2.0)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), List(5) { b.next() })
        b.reset()
        assertEquals(1_000L, b.next())
    }

    @Test
    fun atomicWriteReplacesAndLeavesNoTempFile() {
        val dir = Files.createTempDirectory("atomic").toFile()
        val f = File(dir, "a.json")
        writeTextAtomically(f, "one")
        writeTextAtomically(f, "two")
        assertEquals("two", f.readText())
        assertEquals(listOf("a.json"), dir.list()!!.toList())
    }

    @Test
    fun storeSavesListsAndMarksSynced() {
        val dir = Files.createTempDirectory("store").toFile()
        val store = LocalMeetingStore(dir)
        val a = store.save("u1", "A", "hello", "s", "e", "en")
        store.save("u2", "B", "other user", "s", "e", null)
        assertEquals(1, store.list("u1").size)
        assertEquals(1, store.pendingSync("u1").size)
        store.markSynced("u1", a.eventId, "m-1")
        assertTrue(store.pendingSync("u1").isEmpty())

        // a fresh instance re-reads from disk
        val reloaded = LocalMeetingStore(dir).list("u1").single()
        assertEquals("m-1", reloaded.meetingId)
        assertEquals("hello", reloaded.transcript)
        assertEquals("en", reloaded.language)
        assertNull(LocalMeetingStore(dir).list("u2").single().language)
    }

    @Test
    fun corruptFileIsQuarantinedNotLost() {
        val dir = Files.createTempDirectory("corrupt").toFile()
        File(dir, "bad.json").writeText("{not json")
        val store = LocalMeetingStore(dir)
        store.save("u1", "ok", "t", "s", "e", null)
        assertEquals(1, store.list("u1").size)
        assertFalse(File(dir, "bad.json").exists())
        assertTrue(File(dir, "bad.json.corrupt").exists())
    }
}
