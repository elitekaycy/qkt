package com.qkt.connector.gateway

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * One gateway's backoff after a rate-limited answer (`429`, or `503` when the venue is over its limit or
 * unreachable), shared by every request to that gateway so many pending orders and strategies on it wait
 * together instead of each asking again at once. Each limited answer in a row doubles the pause from
 * [baseMs] up to [maxMs], jittered to between half and all of it; a `Retry-After` the gateway sends is
 * honoured instead (up to [maxRetryAfterMs]). Any other answer ends the streak; the pause already set stands.
 * A request waits out a pause of up to [maxPauseMs]; facing a longer one it is not sent ([pace]).
 */
class GatewayThrottle(
    private val baseMs: Long = 250,
    private val maxMs: Long = 15_000,
    private val maxRetryAfterMs: Long = 300_000,
    private val maxPauseMs: Long = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private var streak = 0
    private var openAtMs = 0L

    /** How long until the gateway may be asked again; 0 when it may be now. */
    @Synchronized
    fun waitMs(): Long = (openAtMs - now()).coerceAtLeast(0)

    /** Waits out the pause before a request to [path], or, past [maxPauseMs], fails it unsent ([last] the cause). */
    fun pace(
        path: String,
        last: String?,
    ) {
        val wait = waitMs()
        if (wait > maxPauseMs) {
            throw GatewayUnavailableException("$path: gateway backing off for $wait ms (${last ?: "rate limited"})")
        }
        if (wait > 0) Thread.sleep(wait)
    }

    /** The gateway answered rate-limited, with [retryAfter] (its `Retry-After` header) when it sent one. */
    @Synchronized
    fun limited(retryAfter: String?) {
        streak++
        val pause =
            retryAfterMs(retryAfter)?.coerceIn(0, maxRetryAfterMs)
                ?: (baseMs shl (streak - 1).coerceAtMost(MAX_SHIFT)).coerceAtMost(maxMs).let { full ->
                    full / 2 + random.nextLong(full / 2 + 1)
                }
        openAtMs = maxOf(openAtMs, now() + pause)
    }

    /** The gateway answered without a rate limit. */
    @Synchronized
    fun answered() {
        streak = 0
    }

    private fun retryAfterMs(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return it * MS_PER_S }
        return runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now()
        }.getOrNull()
    }

    companion object {
        private const val MS_PER_S = 1_000L
        private const val MAX_SHIFT = 20
        private val byGateway = ConcurrentHashMap<String, GatewayThrottle>()

        /** The process-wide throttle of the gateway at [baseUrl], shared by every client and account using it. */
        fun of(baseUrl: String): GatewayThrottle = byGateway.computeIfAbsent(baseUrl.trimEnd('/')) { GatewayThrottle() }
    }
}
