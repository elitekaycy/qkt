package com.qkt.marketdata.source

import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.RollQuoteTaps
import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A session restarted past the December -> March roll serves March. While the stream's lane holds December for its
 * roll's closing leg, the feed reads December too and hands its ticks to the lane, never to the strategy.
 */
class ContinuousLiveFeedRollTapTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val catalog =
        ContractCatalog(
            root.root,
            listOf(
                ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                ListedContract("BTCUSDT_250328", ms("2025-03-28T08:00:00Z")),
            ),
        )
    private val history =
        RollHistory(
            root.root,
            "8d@08:00",
            listOf(
                RollRecord(ms("2024-09-19T08:00:00Z"), "BTCUSDT_240927", "BTCUSDT_241227", "63000", "63800"),
                RollRecord(ms("2024-12-19T08:00:00Z"), "BTCUSDT_241227", "BTCUSDT_250328", "97000", "98500"),
            ),
        )
    private val stream = "BINANCE_UM:BTCUSDT@front"
    private val dec = "BINANCE_UM:BTCUSDT_241227"
    private val mar = "BINANCE_UM:BTCUSDT_250328"
    private var now = ms("2024-12-19T08:00:20Z")
    private val opened = mutableListOf<List<String>>()
    private val heard = mutableListOf<Tick>()
    private val taps = RollQuoteTaps().apply { listen(stream) { heard += it } }

    private fun tick(
        symbol: String,
        second: Int,
        price: String,
    ) = Tick(symbol, BigDecimal(price), ms("2024-12-19T08:00:00Z") + second * 1_000L)

    private fun feed(ticks: List<Tick>): ContinuousLiveFeed {
        val queue = ArrayDeque(ticks)
        return ContinuousLiveFeed(
            chain = { ContinuousChain(root, catalog, history, ContinuousSelector.FRONT) },
            ticks = { symbols ->
                opened += symbols
                object : TickFeed {
                    override fun next(): Tick? = queue.removeFirstOrNull()?.also { now = maxOf(now, it.timestamp) }
                }
            },
            measure = { error("the history holds every roll") },
            clock =
                object : Clock {
                    override fun now() = now
                },
            taps = taps,
        )
    }

    @Test
    fun `the held old contract is read beside the front and its ticks reach the lane, not the strategy`() {
        taps.hold(stream, dec)
        val feed = feed(listOf(tick(mar, 21, "98520"), tick(dec, 22, "97020"), tick(mar, 23, "98530")))

        val served = listOfNotNull(feed.next(), feed.next())

        assertThat(opened).containsExactly(listOf(mar, dec))
        assertThat(served.map { it.symbol }).containsExactly(stream, stream)
        assertThat(served.map { it.timestamp }).containsExactly(ms("2024-12-19T08:00:21Z"), ms("2024-12-19T08:00:23Z"))
        assertThat(heard).containsExactly(tick(dec, 22, "97020"))
    }

    @Test
    fun `an old contract held after the feed opened is read from the next tick on, and dropped once released`() {
        val feed =
            feed(
                listOf(
                    tick(mar, 21, "98520"),
                    tick(mar, 22, "98525"),
                    tick(dec, 23, "97020"),
                    tick(mar, 24, "98530"),
                    tick(dec, 25, "97030"),
                    tick(mar, 26, "98540"),
                ),
            )

        assertThat(feed.next()?.timestamp).isEqualTo(ms("2024-12-19T08:00:21Z"))
        taps.hold(stream, dec)
        assertThat(feed.next()?.timestamp).isEqualTo(ms("2024-12-19T08:00:22Z"))
        assertThat(feed.next()?.timestamp).isEqualTo(ms("2024-12-19T08:00:24Z"))
        taps.release(stream)
        assertThat(feed.next()?.timestamp).isEqualTo(ms("2024-12-19T08:00:26Z"))

        assertThat(opened).containsExactly(listOf(mar), listOf(mar, dec), listOf(mar))
        assertThat(heard).containsExactly(tick(dec, 23, "97020"))
    }

    @Test
    fun `with nothing held the feed reads only the contracts it follows`() {
        val feed = feed(listOf(tick(dec, 21, "97020"), tick(mar, 22, "98520")))

        assertThat(feed.next()?.timestamp).isEqualTo(ms("2024-12-19T08:00:22Z"))
        assertThat(opened).containsExactly(listOf(mar))
        assertThat(heard).isEmpty()
    }
}
