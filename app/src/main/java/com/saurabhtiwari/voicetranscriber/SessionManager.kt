package com.saurabhtiwari.voicetranscriber

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

data class UserSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String
)

internal interface SessionPersistence {
    fun load(): UserSession?
    fun save(session: UserSession)
    fun clear()
}

internal fun interface TokenRefresher {
    /** @throws HttpException when the auth server rejects the refresh token. */
    suspend fun refresh(current: UserSession): UserSession
}

/**
 * Owns the signed-in session and keeps API calls authenticated.
 *
 * - Tokens that are about to expire are refreshed before the request.
 * - A 401 triggers exactly one refresh and one retry.
 * - Concurrent callers share a single refresh (refresh tokens are single-use).
 * - The session is discarded only when the auth server explicitly rejects the refresh token
 *   (HTTP 400-403). Being offline or a 5xx never signs the user out.
 */
internal class SessionManager(
    private val persistence: SessionPersistence,
    private val refresher: TokenRefresher,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: UserSession? = null

    fun current(): UserSession? = cached

    suspend fun active(): UserSession? =
        cached ?: withContext(Dispatchers.IO) { persistence.load() }?.also { cached = it }

    suspend fun adopt(session: UserSession) {
        cached = session
        withContext(Dispatchers.IO) { persistence.save(session) }
    }

    fun invalidate() {
        cached = null
        persistence.clear()
    }

    /** Returns the saved session, refreshing it if needed; stays usable offline. */
    suspend fun restore(): UserSession? {
        val stored = active() ?: return null
        if (stored.expiresAtEpochSeconds > nowSeconds() + EXPIRY_SKEW_SECONDS) return stored
        return try {
            refresh(stored)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            stored
        } catch (_: HttpException) {
            // A transient auth-service response (for example 429 or 5xx) is not proof
            // that the refresh token is invalid. Preserve the local session; the next
            // authenticated request can retry refresh when connectivity/service recovers.
            stored
        }
    }

    /** Null when the server rejected the refresh token. Network and 5xx errors propagate. */
    suspend fun refresh(stale: UserSession): UserSession? = mutex.withLock {
        val latest = cached ?: stale
        if (latest.accessToken != stale.accessToken &&
            latest.expiresAtEpochSeconds > nowSeconds() + EXPIRY_SKEW_SECONDS
        ) {
            return@withLock latest // someone else already refreshed while we waited for the lock
        }
        if (latest.refreshToken.isBlank()) {
            invalidate()
            return@withLock null
        }
        try {
            val refreshed = refresher.refresh(latest)
            cached = refreshed
            withContext(Dispatchers.IO) { persistence.save(refreshed) }
            refreshed
        } catch (error: HttpException) {
            if (error.code in 400..403) {
                invalidate()
                null
            } else {
                throw error
            }
        }
    }

    suspend fun <T> authed(block: suspend (accessToken: String) -> T): T {
        var session = active() ?: throw SessionExpiredException()
        if (session.expiresAtEpochSeconds <= nowSeconds() + EXPIRY_SKEW_SECONDS) {
            session = refresh(session) ?: throw SessionExpiredException()
        }
        return try {
            withContext(Dispatchers.IO) { block(session.accessToken) }
        } catch (error: HttpException) {
            if (error.code != 401) throw error
            val fresh = refresh(session) ?: throw SessionExpiredException()
            withContext(Dispatchers.IO) { block(fresh.accessToken) }
        }
    }

    private companion object {
        const val EXPIRY_SKEW_SECONDS = 90L
    }
}
