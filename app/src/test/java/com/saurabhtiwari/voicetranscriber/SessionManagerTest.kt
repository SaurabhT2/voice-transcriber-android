package com.saurabhtiwari.voicetranscriber

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SessionManagerTest {
    private val now = 1_000_000L

    private fun session(token: String, expiresIn: Long, refresh: String = "r-$token") =
        UserSession(token, refresh, now + expiresIn, "user-1", "a@b.c")

    private class FakeStore(var stored: UserSession?) : SessionPersistence {
        var cleared = 0
        var saves = 0
        override fun load() = stored
        override fun save(session: UserSession) { stored = session; saves++ }
        override fun clear() { stored = null; cleared++ }
    }

    private class FakeRefresher(val behaviour: suspend (UserSession) -> UserSession) : TokenRefresher {
        val calls = AtomicInteger(0)
        override suspend fun refresh(current: UserSession): UserSession {
            calls.incrementAndGet()
            return behaviour(current)
        }
    }

    private fun manager(store: FakeStore, refresher: FakeRefresher) =
        SessionManager(store, refresher, nowSeconds = { now })

    @Test
    fun freshTokenIsUsedWithoutRefreshing() = runBlocking {
        val store = FakeStore(session("t1", 3_600))
        val refresher = FakeRefresher { error("must not refresh") }
        val result = manager(store, refresher).authed { it }
        assertEquals("t1", result)
        assertEquals(0, refresher.calls.get())
    }

    @Test
    fun expiringTokenIsRefreshedBeforeTheCall() = runBlocking {
        val store = FakeStore(session("old", 30)) // inside the 90 s skew
        val refresher = FakeRefresher { session("new", 3_600) }
        val result = manager(store, refresher).authed { it }
        assertEquals("new", result)
        assertEquals("new", store.stored!!.accessToken)
        assertEquals(1, refresher.calls.get())
    }

    @Test
    fun unauthorizedTriggersExactlyOneRefreshAndOneRetry() = runBlocking {
        val store = FakeStore(session("old", 3_600))
        val refresher = FakeRefresher { session("new", 3_600) }
        val seen = ArrayList<String>()
        val result = manager(store, refresher).authed { token ->
            seen.add(token)
            if (token == "old") throw HttpException(401, "expired") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf("old", "new"), seen)
        assertEquals(1, refresher.calls.get())
    }

    @Test
    fun secondUnauthorizedIsNotRetriedForever() = runBlocking {
        val store = FakeStore(session("old", 3_600))
        val refresher = FakeRefresher { session("new", 3_600) }
        var attempts = 0
        var thrown: Throwable? = null
        try {
            manager(store, refresher).authed<String> { attempts++; throw HttpException(401, "nope") }
        } catch (e: HttpException) { thrown = e }
        assertTrue(thrown is HttpException)
        assertEquals(2, attempts)
        assertEquals(1, refresher.calls.get())
    }

    @Test
    fun otherHttpErrorsDoNotRefresh() = runBlocking {
        val store = FakeStore(session("t", 3_600))
        val refresher = FakeRefresher { error("must not refresh") }
        var code = 0
        try { manager(store, refresher).authed<String> { throw HttpException(404, "missing") } }
        catch (e: HttpException) { code = e.code }
        assertEquals(404, code)
        assertEquals(0, refresher.calls.get())
    }

    @Test
    fun rejectedRefreshTokenSignsOutAndClearsStorage() = runBlocking {
        val store = FakeStore(session("old", 10))
        val refresher = FakeRefresher { throw HttpException(400, "invalid_grant") }
        var expired = false
        try { manager(store, refresher).authed<String> { "never" } }
        catch (_: SessionExpiredException) { expired = true }
        assertTrue(expired)
        assertNull(store.stored)
        assertEquals(1, store.cleared)
    }

    @Test
    fun offlineRestoreKeepsTheSavedSession() = runBlocking {
        val store = FakeStore(session("old", -500)) // already expired
        val refresher = FakeRefresher { throw IOException("no network") }
        val restored = manager(store, refresher).restore()
        assertEquals("old", restored!!.accessToken)
        assertEquals(0, store.cleared)
        assertTrue(store.stored != null)
    }

    @Test
    fun serverErrorDuringRefreshDoesNotSignOut() = runBlocking {
        val store = FakeStore(session("old", 10))
        val refresher = FakeRefresher { throw HttpException(503, "unavailable") }
        var code = 0
        try { manager(store, refresher).authed<String> { "never" } } catch (e: HttpException) { code = e.code }
        assertEquals(503, code)
        assertEquals(0, store.cleared)
        assertTrue(store.stored != null)
    }

    @Test
    fun restoreWithFreshTokenDoesNotRefresh() = runBlocking {
        val store = FakeStore(session("t", 3_600))
        val refresher = FakeRefresher { error("must not refresh") }
        assertEquals("t", manager(store, refresher).restore()!!.accessToken)
        assertNull(manager(FakeStore(null), refresher).restore())
    }

    @Test
    fun concurrentCallsShareOneRefresh() = runBlocking {
        val store = FakeStore(session("old", 10))
        val refresher = FakeRefresher { delay(50); session("new", 3_600) }
        val m = manager(store, refresher)
        val results = (1..12).map { async { m.authed { it } } }.awaitAll()
        assertTrue(results.all { it == "new" })
        assertEquals(1, refresher.calls.get())
    }

    @Test
    fun noSavedSessionMeansSignInRequired() = runBlocking {
        var expired = false
        try { manager(FakeStore(null), FakeRefresher { error("x") }).authed<String> { "x" } }
        catch (_: SessionExpiredException) { expired = true }
        assertTrue(expired)
    }
}
