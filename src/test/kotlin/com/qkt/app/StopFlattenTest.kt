package com.qkt.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `stop --flatten` is confirmed against the session's book and the venue, and a close that never reached
 * the venue is sent again. Live, 2026-10-21: the paper gateway answered 503 for 53 s, the close submit
 * was never placed, and the stop left 0.0001 ETH open at the venue.
 */
class StopFlattenTest {
    private var now = 0L
    private var flattens = 0
    private var running: () -> Boolean = { false }
    private var book = StopFlatten.Book(emptyList(), closing = false)
    private var venue: () -> List<String> = { emptyList() }
    private val onFlatten = mutableListOf<() -> Unit>()

    private fun stop(
        timeoutMs: Long = 90_000,
        names: Boolean = true,
    ) = StopFlatten(
        requestFlatten = {
            flattens++
            onFlatten.getOrNull(flattens - 1)?.invoke()
        },
        flattenRunning = { running() },
        book = { book },
        venueHeld = { venue() },
        venueNamesPositions = names,
        nowMs = { now },
        sleep = { now += it },
        timeoutMs = timeoutMs,
    ).run()

    @Test
    fun `a close the venue never took is sent again until the book is flat`() {
        // First flatten: the close is rejected after the outage (not placed), the leg stays open.
        onFlatten += { book = StopFlatten.Book(listOf("leg-3 (ETH)"), closing = false) }
        onFlatten += { book = StopFlatten.Book(emptyList(), closing = false) }

        val result = stop(names = false)

        assertThat(result.verifiedFlat).isTrue
        assertThat(flattens).isEqualTo(2)
    }

    @Test
    fun `a close still working is waited for, never doubled`() {
        onFlatten += { book = StopFlatten.Book(listOf("leg-3 (ETH)"), closing = true) }
        var polls = 0
        venue = {
            polls++
            if (polls == 5) book = StopFlatten.Book(emptyList(), closing = false)
            emptyList()
        }

        assertThat(stop(names = false).verifiedFlat).isTrue
        assertThat(flattens).isEqualTo(1)
    }

    @Test
    fun `a ticket the venue still lists is waited for and reported, not closed again from an empty book`() {
        venue = { listOf("3391116445") }

        val result = stop(timeoutMs = 5_000)

        assertThat(result.verifiedFlat).isFalse
        assertThat(result.remainingTickets).containsExactly("3391116445")
        assertThat(flattens).isEqualTo(1)
    }

    @Test
    fun `an unreadable venue is never taken for flat`() {
        venue = { error("GET /get_positions failed after 3 retries") }

        val result = stop(timeoutMs = 3_000)

        assertThat(result.verifiedFlat).isFalse
        assertThat(result.detail).contains("GET /get_positions failed")
    }

    @Test
    fun `a flatten still running on the engine thread is waited for`() {
        // Queued behind other engine work: the leg is open and no close is working yet.
        book = StopFlatten.Book(listOf("leg-1 (BTC)"), closing = false)
        var busy = 3
        running = {
            (busy-- > 0).also { if (!it) book = StopFlatten.Book(emptyList(), closing = false) }
        }

        val result = stop(names = false)

        assertThat(result.verifiedFlat).isTrue
        assertThat(flattens).isEqualTo(1)
    }
}
