package com.qkt.marketdata.depth

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The live depth feed reads each contract once a poll, ticks every stream of it from that one snapshot, and outlives a failed read. */
class BookDepthPollTest {
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val streams = BookDepthSymbol.FIELDS.map { BookDepthSymbol.of(it, perp) }
    private val published = CopyOnWriteArrayList<BookDepth>()
    private val asked = CopyOnWriteArrayList<Pair<String, Long>>()

    @Volatile private var failNext = false

    private val source =
        BookDepthSource { contract, fromMs, toMs ->
            asked += contract to fromMs
            if (failNext) {
                failNext = false
                error("gateway down")
            }
            published.filter { it.timeMs in fromMs..toMs }
        }

    private fun book(
        timeMs: Long,
        bid: String,
        ask: String,
    ) = BookDepth(
        timeMs,
        listOf(BookLevel(BigDecimal("86000"), BigDecimal(bid))),
        listOf(BookLevel(BigDecimal("86001"), BigDecimal(ask))),
    )

    private fun until(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `the start read comes first, then each newer snapshot once on every stream, and a failed poll reconnects`() {
        published += book(1_000, "3", "1")
        val ticks = CopyOnWriteArrayList<Tick>()
        val events = CopyOnWriteArrayList<String>()
        val poll = BookDepthPoll(source, mapOf(perp to streams), mapOf(perp to published.toList()), { 10_000 }, 20)

        failNext = true
        poll.start({ ticks += it }, { events += "error" }, { events += "down" }, { events += "up" })
        until { events == listOf("down", "up") }
        published += book(2_000, "1", "3")
        until { ticks.size == 6 }
        Thread.sleep(100)
        poll.stop()

        assertThat(
            ticks.map {
                "${it.symbol.substringBefore(
                    ":DERIBIT",
                )}@${it.timestamp}=${it.price.stripTrailingZeros().toPlainString()}"
            },
        ).containsExactly(
            "DEPTH:BID@1000=3",
            "DEPTH:ASK@1000=1",
            "DEPTH:IMBALANCE@1000=0.5",
            "DEPTH:BID@2000=1",
            "DEPTH:ASK@2000=3",
            "DEPTH:IMBALANCE@2000=-0.5",
        )
        assertThat(asked.map { it.first }.distinct()).containsExactly(perp)
        assertThat(asked.map { it.second }.distinct()).containsExactly(1_001L, 2_001L)
    }
}
