package com.qkt.connector.gateway

import com.qkt.common.Clock
import java.math.BigDecimal
import java.math.MathContext
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

private val HUNDRED = BigDecimal(100)

/** The account's margin level as a percent (equity / used margin x 100), or null when it uses no margin. */
internal fun WireAccount.marginLevel(): BigDecimal? {
    val used = BigDecimal(marginUsed)
    return if (used.signum() > 0) BigDecimal(equity).divide(used, MathContext.DECIMAL64) * HUNDRED else null
}

/**
 * The margin level of one gateway account for the margin floor, from what `GET /v1/account` states. A background
 * thread does the reading, every [pollMs], so [read] never touches the network: the floor asks on every entry, and a
 * hung gateway would otherwise hold the entry for up to `http_timeout_ms` x `retry_attempts`.
 *
 * An account using no margin reads 0, as an MT5 account does, which the floor approves: it has nothing at risk of a
 * stop-out. A level not yet read, or last read [staleMs] ago or more, is null, which the floor counts as a missing
 * reading. A failed read leaves the last reading to age out, so one blip costs no entries and an outage ends in null.
 *
 * MT5 keeps its cache warm the same way but falls back to a synchronous read when it is stale; this has no fallback.
 */
internal class GatewayMarginLevel(
    private val readAccount: () -> WireAccount,
    private val clock: Clock,
    private val label: String,
    private val pollMs: Long = POLL_MS,
    private val staleMs: Long = STALE_MS,
) {
    private class Reading(
        val atMs: Long,
        val level: BigDecimal,
    )

    private val log = LoggerFactory.getLogger(GatewayMarginLevel::class.java)
    private val started = AtomicBoolean(false)

    @Volatile private var last: Reading? = null

    @Volatile private var closed = false

    @Volatile private var thread: Thread? = null
    private var failing = false

    /** The level, at most [staleMs] old, or null. Never blocks. */
    fun read(): BigDecimal? {
        start()
        val reading = last ?: return null
        return reading.level.takeIf { clock.now() - reading.atMs < staleMs }
    }

    /** Starts the poller, once: a session whose floor is 0 never asks, and so never reads `/v1/account` for it. */
    fun start() {
        if (closed || !started.compareAndSet(false, true)) return
        thread =
            Thread({ pollUntilClosed() }, "qkt-gateway-margin-$label").apply {
                isDaemon = true
                start()
            }
    }

    /** One read of the account: a new reading, or on failure the last one left to age. */
    internal fun poll() {
        try {
            val level = readAccount().marginLevel() ?: BigDecimal.ZERO
            last = Reading(clock.now(), level)
            if (failing) log.info("gateway {} margin level readable again", label)
            failing = false
        } catch (e: RuntimeException) {
            if (!failing) log.warn("gateway {} margin level unreadable: {}", label, e.message)
            failing = true
        }
    }

    /** Stops polling; a read in flight ends on its own timeout, on a daemon thread. */
    fun close() {
        closed = true
        thread?.interrupt()
    }

    private fun pollUntilClosed() {
        try {
            while (!closed) {
                poll()
                Thread.sleep(pollMs)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val STALE_MS = 15_000L
    }
}
