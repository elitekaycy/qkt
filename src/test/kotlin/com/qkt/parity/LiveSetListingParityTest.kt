package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.backtest.Backtest
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.cli.DynamicSetRuns
import com.qkt.cli.SymbolSetResolution
import com.qkt.cli.daemon.LiveSetPoller
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.sets.DynamicSetFixtures
import com.qkt.dsl.sets.DynamicSetFixtures.Line
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.EventContracts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.ExpandableTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.parity.DslParityState.TradeState
import com.qkt.parity.DslParityState.tradeState
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A moving set run live for 30 hours, past the 24-hour candidate horizon of its start, with a contract listed only
 * mid-run: the live session's polls (at 24h and 26h) subscribe the next candidates before they can join, so its
 * membership journal and trades equal a backtest's over a catalog that held every contract from the start.
 */
class LiveSetListingParityTest {
    private val hour = 3_600_000L
    private val t0 = 1_699_999_200_000L
    private val id = "roll"
    private val balance = BigDecimal("10000")

    private fun contract(
        code: String,
        open: Int,
    ): InstrumentMeta =
        EventContracts.meta(
            "BACKTEST:$code",
            EventContracts
                .terms()
                .copy(openAtMs = t0 + open * hour, closeAtMs = t0 + 60 * hour, expiryMs = t0 + 60 * hour),
        )

    private val atStart = listOf(contract("A", 0), contract("B", 10), contract("C", 25))
    private val late = contract("D", 27)

    private val ast =
        DynamicSetFixtures.ast(
            "STRATEGY roll VERSION 1\nSYMBOLS\n" +
                "    s = BACKTEST:* WHERE kind = event TOP 1 BY symbol DESC DYNAMIC EVERY 1h\n" +
                "RULES\n    FOR EACH m IN s DO\n      WHEN m.close > 100 AND POSITION.m = 0 THEN BUY m SIZING 1",
        )

    private val ticks: List<Tick> =
        (0 until 30 * 6).flatMap { i ->
            listOf("A", "B", "C", "D").map { Tick("BACKTEST:$it", BigDecimal("101"), t0 + i * 600_000L) }
        }

    private fun plan(
        rows: List<InstrumentMeta>,
        toMs: Long,
    ) = requireNotNull(DynamicSetRuns.plan(ast, SymbolSetResolution.FileCatalog(rows), t0, toMs, false) {})

    private class Run(
        val journal: List<Line>,
        val trades: List<TradeState>,
    )

    private fun backtest(): Run {
        val journal = mutableListOf<Line>()
        val full = plan(atStart + late, t0 + 30 * hour)
        val strategy = full.strategy(emptyMap()) { journal += DynamicSetFixtures.line(it) }
        val result =
            Backtest(
                strategies = listOf(id to strategy),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_HOUR,
                initialTimestamp = t0,
                startingBalance = balance,
                startingBalances = mapOf(id to balance),
            ).run()
        return Run(journal, result.trades.map { tradeState(it.strategyId, it.trade, it.realized) })
    }

    /** The venue's live feed: the tape, but only the symbols subscribed so far; it polls at 24h and 26h. */
    private class Venue(
        private val ticks: List<Tick>,
        private val polls: Map<Long, () -> Unit>,
    ) : MarketSource {
        val feed =
            object : ExpandableTickFeed {
                private val served = HashSet<String>()
                private var next = 0
                private val due = polls.toSortedMap()

                @Synchronized
                override fun add(symbols: Collection<String>): Set<String> {
                    served += symbols
                    return symbols.toSet()
                }

                override fun next(): Tick? {
                    while (next < ticks.size) {
                        val tick = ticks[next++]
                        while (due.isNotEmpty() && due.firstKey() <= tick.timestamp) {
                            due.remove(due.firstKey())?.invoke()
                        }
                        if (tick.symbol in served) return tick
                    }
                    return null
                }

                override fun close() = Unit
            }

        override val name = "Venue"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed = feed.also { it.add(symbols) }
    }

    private fun live(): Run {
        val listed = atStart.toMutableList()
        val catalog =
            object : SetCatalog {
                override val source = "the venue listing"
                override val survivorsOnly = false

                override fun venue(
                    alias: String,
                    spec: SymbolSetSpec.Filtered,
                ) = VenueCatalog(listed.toList())
            }
        val start = plan(atStart, t0 + 24 * hour)
        val host = start.strategy(emptyMap(), live = true) as DynamicSetHost
        lateinit var venue: Venue
        val poller = LiveSetPoller(host, catalog, subscribe = { venue.feed.add(it) })
        venue =
            Venue(
                ticks,
                mapOf(
                    t0 + 24 * hour to { poller.poll(t0 + 24 * hour) },
                    t0 + 26 * hour to {
                        listed += late
                        poller.poll(t0 + 26 * hour)
                    },
                ),
            )
        val clock = FixedClock(t0)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val journal = mutableListOf<Line>()
        bus.subscribe<SetMembershipChanged> { journal += DynamicSetFixtures.line(it) }
        val trades = mutableListOf<TradeState>()
        val handle =
            LiveSession(
                strategies = listOf(id to host),
                haltRules = emptyList(),
                source = venue,
                symbols = start.ast.streams.map { it.qktSymbol },
                candleWindow = TimeWindow.ONE_HOUR,
                clock = clock,
                calendar = TradingCalendar.crypto(),
                onTrade = { trade, realized, owner -> trades += tradeState(owner, trade, realized) },
                initialBalance = balance,
                startingBalances = mapOf(id to balance),
                busOverride = bus,
            ).start()
        check(handle.awaitTermination(Duration.ofSeconds(20))) { "live session did not terminate" }
        return Run(journal.toList(), trades.toList())
    }

    @Test
    fun `a live set past its start horizon with a late listing joins and trades as the backtest does`() {
        val (backtest, live) = BacktestLiveParityFixtures.withQuietParityLogs { backtest() to live() }

        assertThat(backtest.journal.map { it.member to it.change.name }).containsExactly(
            "BACKTEST:A" to "JOIN",
            "BACKTEST:A" to "STICK",
            "BACKTEST:B" to "JOIN",
            "BACKTEST:B" to "STICK",
            "BACKTEST:C" to "JOIN",
            "BACKTEST:C" to "STICK",
            "BACKTEST:D" to "JOIN",
        )
        assertThat(live.journal).containsExactlyElementsOf(backtest.journal)
        assertThat(live.trades).containsExactlyElementsOf(backtest.trades)
        assertThat(backtest.trades.map { it.symbol })
            .containsExactly("BACKTEST:A", "BACKTEST:B", "BACKTEST:C", "BACKTEST:D")
    }
}
