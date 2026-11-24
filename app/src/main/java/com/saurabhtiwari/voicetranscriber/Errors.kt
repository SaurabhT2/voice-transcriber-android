package com.saurabhtiwari.voicetranscriber

import java.io.IOException
import kotlin.math.min

/**
 * A non-2xx HTTP response. Extends [IOException] so existing `catch (IOException)` blocks keep
 * working, while callers that care (token refresh, sync retry policy) can read [code].
 */
class HttpException(val code: Int, message: String) : IOException(message)

/** The saved session can no longer be refreshed; the user has to sign in again. */
class SessionExpiredException(
    message: String = "Your session has expired. Please sign in again."
) : IOException(message)

internal enum class FailureKind { RETRYABLE, PERMANENT }

/**
 * Decides whether a failed network operation is worth retrying later.
 * Connectivity problems and transient server errors are retryable; client errors are not.
 */
internal fun classifyFailure(error: Throwable): FailureKind = when (error) {
    is SessionExpiredException -> FailureKind.PERMANENT
    is HttpException ->
        if (error.code == 401 || error.code == 408 || error.code == 425 ||
            error.code == 429 || error.code >= 500
        ) FailureKind.RETRYABLE else FailureKind.PERMANENT
    is IOException -> FailureKind.RETRYABLE
    else -> FailureKind.PERMANENT
}

/** Growing delay between status polls: 3 s, 4.5 s, 6.75 s ... capped at [maxMs]. */
internal class PollBackoff(
    private val initialMs: Long = 3_000L,
    private val maxMs: Long = 15_000L,
    private val factor: Double = 1.5
) {
    private var current = initialMs

    fun next(): Long {
        val value = current
        current = min(maxMs, (current * factor).toLong())
        return value
    }

    fun reset() {
        current = initialMs
    }
}
