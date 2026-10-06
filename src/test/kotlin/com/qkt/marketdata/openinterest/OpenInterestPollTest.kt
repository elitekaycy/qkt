package com.qkt.marketdata.openinterest

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The live open-interest feed delivers each figure once, at the instant it was known, and outlives a failed read. */
class OpenInterestPollTest {
    private val stream = "OI:DERIBIT:BTC_USDC_PERPETUAL"
    private val published = CopyOnWriteArrayList<OpenInterest>()
    private val asked = CopyOnWriteArrayList<Long>()

    @Volatile private var failNext = false

    private val source =
        OpenInterestSource { _, fromMs, toMs ->
            asked += fromMs
            if (failNext) {
                failNext = false
                error("gateway down")
            }
            published.filter { it.timeMs in fromMs..toMs }
        }

    private fun until(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `the start read comes first, then each newer figure once, and a failed poll disconnects then reconnects`() {
        published += OpenInterest(1_000, BigDecimal("1477.6341"))
        val ticks = CopyOnWriteArrayList<Tick>()
        val events = CopyOnWriteArrayList<String>()
        val poll = OpenInterestPoll(source, mapOf(stream to published.toList()), { 10_000 }, 20)

        failNext = true
        poll.start({ ticks += it }, { events += "error" }, { events += "down" }, { events += "up" })
        until { events == listOf("down", "up") }
        published += OpenInterest(2_000, BigDecimal("1480"))
        until { ticks.size == 2 }
        Thread.sleep(100)
        poll.stop()

        assertThat(ticks.map { it.timestamp to it.price.stripTrailingZeros().toPlainString() })
            .containsExactly(1_000L to "1477.6341", 2_000L to "1480")
        assertThat(ticks.map { it.symbol }.distinct()).containsExactly(stream)
        assertThat(asked.distinct()).containsExactly(1_001L, 2_001L)
    }
}
