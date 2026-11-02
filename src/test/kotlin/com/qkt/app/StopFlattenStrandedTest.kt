package com.qkt.app

import com.qkt.broker.StreamStop
import com.qkt.broker.continuous.ScriptedLaneFixture
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `stop --flatten` on a strategy holding what a stopped stream refuses to close. Paper venue, 2026-11-02: a roll's
 * close never filled, the stop printed `stopped` with -0.001 still held, and sent a refused order every 0.5 s.
 */
class StopFlattenStrandedTest {
    private var now = 0L
    private val sentAt = mutableListOf<Long>()

    private fun stop(
        book: () -> StopFlatten.Book,
        stops: () -> List<StreamStop>,
        onFlatten: () -> Unit = {},
    ) = StopFlatten(
        requestFlatten = {
            sentAt += now
            onFlatten()
        },
        flattenRunning = { false },
        book = book,
        venueHeld = { emptyList() },
        venueNamesPositions = false,
        nowMs = { now },
        sleep = { now += it },
        timeoutMs = 120_000,
        streamStops = stops,
    ).run()

    @Test
    fun `a lane stopped on a held contract is reported as held, and its refused close is not sent again`() {
        val f = ScriptedLaneFixture()
        f.holdIntoRoll()
        f.venueBus.publish(BrokerEvent.OrderRejected(f.leg(":close").id, null, "refused", "s", f.clock.time))
        var refused = 0
        val close = OrderRequest.Market("flat", f.front, Side.SELL, BigDecimal("0.010"), TimeInForce.GTC, 0L, "s")

        val result =
            stop(
                book = { StopFlatten.Book(listOf("s-primary-1 (${f.front})"), closing = false) },
                stops = { f.broker.streamStops("s") },
                onFlatten = { if (!f.broker.submit(close).accepted) refused++ },
            )

        assertThat(result.verifiedFlat).isFalse
        assertThat(refused).isEqualTo(1)
        assertThat(sentAt).hasSize(1)
        assertThat(now).isLessThan(5_000)
        assertThat(result.remainingTickets.single()).contains("0.010").contains(f.sep)
        assertThat(result.detail).contains("refused", "qkt resume", "settles", "venue")
    }

    @Test
    fun `a holding only the lane knows of is never taken for flat`() {
        // A failed unwind: the ledger has nothing, the contract still holds what the unwind did not close.
        val stop = StreamStop("s", "D:BTC@front", "D:BTC@front stopped", listOf("0.5 of D:BTC_5NOV26"))

        val result = stop(book = { StopFlatten.Book(emptyList(), closing = false) }, stops = { listOf(stop) })

        assertThat(result.verifiedFlat).isFalse
        assertThat(result.remainingTickets).containsExactly("0.5 of D:BTC_5NOV26")
    }

    @Test
    fun `a stop with nothing held does not stand in the way of flat`() {
        val stop = StreamStop("s", "D:BTC@front", "D:BTC@front stopped")

        val result = stop(book = { StopFlatten.Book(emptyList(), closing = false) }, stops = { listOf(stop) })

        assertThat(result.verifiedFlat).isTrue
    }

    @Test
    fun `a close elsewhere is still seen through before the stranded holding is reported`() {
        val stop = StreamStop("s", "D:BTC@front", "D:BTC@front stopped", listOf("0.5 of D:BTC_5NOV26"))
        var open = listOf("a (D:BTC@front)", "b (D:ETH)")

        val result =
            stop(
                book = {
                    if (now >= 2_000) open = listOf("a (D:BTC@front)")
                    StopFlatten.Book(open, closing = open.size > 1)
                },
                stops = { listOf(stop) },
            )

        assertThat(result.verifiedFlat).isFalse
        assertThat(now).isGreaterThanOrEqualTo(2_000)
        assertThat(sentAt).hasSize(1)
    }

    @Test
    fun `a close the venue keeps not taking is sent again with growing gaps, within the bound`() {
        val result =
            stop(book = { StopFlatten.Book(listOf("b (D:ETH)"), closing = false) }, stops = { emptyList() })

        assertThat(result.verifiedFlat).isFalse
        assertThat(result.detail).contains("b (D:ETH)")
        assertThat(sentAt.size).isBetween(3, 10)
        val gaps = sentAt.zipWithNext { a, b -> b - a }
        assertThat(gaps).isSorted
        assertThat(gaps.last()).isLessThanOrEqualTo(30_000)
    }
}
