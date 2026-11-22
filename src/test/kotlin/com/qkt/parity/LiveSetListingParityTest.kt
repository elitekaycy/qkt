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
import com.qkt.marketdata.source.ExpandableLiveSource
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

    /** The symbols of each feed the merged venue opened in the last [live] run through it. */
    private var feedsOpened: List<List<String>> = emptyList()

    /** The live run; [merged] reads a venue whose feeds cannot take symbols, through the production merged feed. */
    private fun live(merged: Boolean = false): Run {
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
        lateinit var subscribe: (Collection<String>) -> Set<String>
        val poller = LiveSetPoller(host, catalog, subscribe = { subscribe(it) })
        val polls =
            mapOf(
                t0 + 24 * hour to { poller.poll(t0 + 24 * hour) },
                t0 + 26 * hour to {
                    listed += late
                    poller.poll(t0 + 26 * hour)
                },
            )
        val plain = LiveSetVenue(ticks, polls)
        val split = LiveSetMergedVenue(ticks, polls)
        val expandable = ExpandableLiveSource(split)
        subscribe = if (merged) expandable::subscribe else plain.feed::add
        val venue = if (merged) split.reading(expandable) else plain
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
        feedsOpened = split.opened
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

    @Test
    fun `a live set read through the merged feed joins and trades as the backtest does, on one added feed`() {
        val (backtest, live) = BacktestLiveParityFixtures.withQuietParityLogs { backtest() to live(merged = true) }

        assertThat(live.journal).containsExactlyElementsOf(backtest.journal)
        assertThat(live.trades).containsExactlyElementsOf(backtest.trades)
        assertThat(feedsOpened).containsExactly(
            listOf("BACKTEST:A", "BACKTEST:B"),
            listOf("BACKTEST:C"),
            listOf("BACKTEST:C", "BACKTEST:D"),
        )
    }
}
