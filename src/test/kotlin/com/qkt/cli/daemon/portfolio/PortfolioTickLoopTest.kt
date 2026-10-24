package com.qkt.cli.daemon.portfolio

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The live book samples on the feed the backtest samples on: once per boundary of any child symbol's bar (#1490). */
class PortfolioTickLoopTest {
    private val hour = 3_600_000L
    private val subscribed = mutableListOf<List<String>>()

    private fun source(ticks: List<Tick>) =
        object : MarketSource {
            override val name = "Fake"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed {
                subscribed.add(symbols)
                val it = ticks.iterator()
                return object : TickFeed {
                    override fun next() = if (it.hasNext()) it.next() else null
                }
            }
        }

    private fun tick(
        symbol: String,
        atMs: Long,
    ) = Tick(symbol, Money.of("100"), atMs)

    private val ticks =
        listOf(
            tick("A:EURUSD", 10L),
            tick("A:BTCUSD", 20L),
            tick("A:EURUSD", hour + 10L),
            tick("A:BTCUSD", hour + 20L),
            tick("A:BTCUSD", 2 * hour + 5L),
            tick("A:BTCUSD", 3 * hour + 5L),
            tick("A:EURUSD", 3 * hour + 6L),
        )

    @Test
    fun `a book feed samples every child symbol's boundary once and the gate sees only its own streams`() {
        val events = mutableListOf<String>()
        val once = com.qkt.backtest.OncePerBoundary()
        PortfolioTickLoop(
            source = source(ticks),
            gateSymbols = listOf("A:BTCUSD"),
            gateWindow = TimeWindow.ONE_HOUR,
            bookFeed = BookFeed(listOf("A:EURUSD", "A:BTCUSD"), TimeWindow.ONE_HOUR),
            onGateCandle = { events.add("gate ${it.symbol} ${it.endTime / hour}") },
            sample = { if (once.first(it)) events.add("sample ${it / hour}") },
        ).run { true }

        assertThat(subscribed.single()).containsExactlyInAnyOrder("A:BTCUSD", "A:EURUSD")
        assertThat(events).containsExactly(
            "sample 1",
            "gate A:BTCUSD 1",
            "gate A:BTCUSD 2",
            "sample 2",
            "gate A:BTCUSD 3",
            "sample 3",
        )
    }

    @Test
    fun `without a book feed the book samples on the gate candles as before`() {
        val samples = mutableListOf<Long>()
        val gate = mutableListOf<String>()
        PortfolioTickLoop(
            source = source(ticks),
            gateSymbols = listOf("A:BTCUSD"),
            gateWindow = TimeWindow.ONE_HOUR,
            bookFeed = null,
            onGateCandle = { gate.add(it.symbol) },
            sample = { samples.add(it / hour) },
        ).run { true }

        assertThat(subscribed.single()).containsExactly("A:BTCUSD")
        assertThat(gate).containsOnly("A:BTCUSD").hasSize(3)
        assertThat(samples).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `a children-only portfolio still samples its book on the book feed`() {
        val samples = mutableListOf<Long>()
        val once = com.qkt.backtest.OncePerBoundary()
        val loop =
            PortfolioTickLoop(
                source = source(ticks),
                gateSymbols = emptyList(),
                gateWindow = null,
                bookFeed = BookFeed(listOf("A:EURUSD", "A:BTCUSD"), TimeWindow.ONE_HOUR),
                onGateCandle = { error("no gate streams") },
                sample = { if (once.first(it)) samples.add(it / hour) },
            )
        loop.run { true }

        assertThat(loop.symbols).containsExactly("A:EURUSD", "A:BTCUSD")
        assertThat(samples).containsExactly(1L, 2L, 3L)
    }
}
