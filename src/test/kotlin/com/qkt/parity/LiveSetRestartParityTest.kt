package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.backtest.Backtest
import com.qkt.broker.Broker
import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.cli.DynamicSetRuns
import com.qkt.cli.SymbolSetResolution
import com.qkt.cli.daemon.LiveSetPoller
import com.qkt.cli.daemon.liveStrategy
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
import com.qkt.instrument.universe.MembershipChange
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.marketdata.Tick
import com.qkt.parity.DslParityState.TradeState
import com.qkt.parity.DslParityState.tradeState
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A moving set run live for 30 hours with a restart at 20h, over the venue listing a deploy reads (a contract listed
 * only from 26h): the restarted session declares the positions it held, restores them as members (the one still ranked)
 * and sticky (the one that left), and from then on joins and trades exactly as an uninterrupted backtest does.
 */
class LiveSetRestartParityTest {
    @TempDir
    lateinit var tmp: Path

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
    private val source =
        "STRATEGY roll VERSION 1\nSYMBOLS\n" +
            "    s = BACKTEST:* WHERE kind = event TOP 1 BY symbol DESC DYNAMIC EVERY 1h\n" +
            "RULES\n    FOR EACH m IN s DO\n      WHEN m.close > 100 AND POSITION.m = 0 THEN BUY m SIZING 1"

    private val ticks: List<Tick> =
        (0 until 30 * 6).flatMap { i ->
            listOf("A", "B", "C", "D").map { Tick("BACKTEST:$it", BigDecimal("101"), t0 + i * 600_000L) }
        }

    /** A paper account that keeps no positions across sessions: the persisted books stand, as on a netting venue. */
    private val account: BrokerFactory = { bus, clock, prices, _, _ ->
        object : Broker by PaperBroker(bus, clock, prices) {
            override fun isAccountWide(symbol: String) = true
        }
    }

    private class Run(
        val journal: List<Line>,
        val trades: List<TradeState>,
    )

    private fun backtest(): Run {
        val journal = mutableListOf<Line>()
        val ast = DynamicSetFixtures.ast(source)
        val catalog = SymbolSetResolution.FileCatalog(atStart + late)
        val plan = requireNotNull(DynamicSetRuns.plan(ast, catalog, t0, t0 + 30 * hour, false) {})
        val result =
            Backtest(
                strategies = listOf(id to plan.strategy(emptyMap()) { journal += DynamicSetFixtures.line(it) }),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_HOUR,
                initialTimestamp = t0,
                startingBalance = balance,
                startingBalances = mapOf(id to balance),
            ).run()
        return Run(journal, result.trades.map { tradeState(it.strategyId, it.trade, it.realized) })
    }

    private val listed = atStart.toMutableList()
    private val catalog =
        object : SetCatalog {
            override val source = "the venue listing"
            override val survivorsOnly = false

            override fun venue(
                alias: String,
                spec: SymbolSetSpec.Filtered,
            ) = VenueCatalog(listed.toList())
        }

    /** One live session from [fromHour] to [toHour] over the same state; polls at the hours in [polls]. */
    private fun session(
        fromHour: Int,
        toHour: Int,
        polls: List<Int>,
        journal: MutableList<Line>,
        trades: MutableList<TradeState>,
    ) {
        val file = tmp.resolve("roll.qkt").also { Files.writeString(it, source) }
        val persistor = FileStatePersistor(tmp.resolve("state"))
        val (ast, strategy) = liveStrategy(file, catalog, t0 + fromHour * hour, persistor)
        val host = strategy as DynamicSetHost
        lateinit var venue: LiveSetVenue
        val poller = LiveSetPoller(host, catalog, subscribe = { venue.feed.add(it) })
        val window = ticks.filter { it.timestamp >= t0 + fromHour * hour && it.timestamp < t0 + toHour * hour }
        venue =
            LiveSetVenue(
                window,
                polls.associate { h ->
                    (t0 + h * hour) to {
                        if (h >= 26) listed += late
                        poller.poll(t0 + h * hour)
                    }
                },
            )
        val clock = FixedClock(t0 + fromHour * hour)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<SetMembershipChanged> { journal += DynamicSetFixtures.line(it) }
        val handle =
            LiveSession(
                strategies = listOf(id to host),
                haltRules = emptyList(),
                source = venue,
                symbols = ast.streams.map { it.qktSymbol },
                candleWindow = TimeWindow.ONE_HOUR,
                clock = clock,
                calendar = TradingCalendar.crypto(),
                onTrade = { trade, realized, owner -> trades += tradeState(owner, trade, realized) },
                initialBalance = balance,
                startingBalances = mapOf(id to balance),
                brokerFactories = mapOf("backtest" to account),
                persistor = persistor,
                busOverride = bus,
            ).start()
        check(handle.awaitTermination(Duration.ofSeconds(20))) { "live session did not terminate" }
    }

    @Test
    fun `a restart mid-run restores held members and then joins and trades as an uninterrupted backtest`() {
        val backtest = BacktestLiveParityFixtures.withQuietParityLogs { backtest() }
        val journal = mutableListOf<Line>()
        val trades = mutableListOf<TradeState>()
        BacktestLiveParityFixtures.withQuietParityLogs {
            session(0, 20, emptyList(), journal, trades)
            session(20, 30, listOf(24, 26), journal, trades)
        }

        val restored = journal.filter { it.change == MembershipChange.RESTORE }
        assertThat(restored.map { it.member to it.rank }).containsExactly("BACKTEST:B" to 1, "BACKTEST:A" to null)
        assertThat(restored.map { it.windowEndMs }.distinct()).containsExactly(t0 + 20 * hour)
        assertThat(journal - restored.toSet()).containsExactlyElementsOf(backtest.journal)
        assertThat(trades.map { Triple(it.symbol, it.side, it.timestamp) })
            .containsExactlyElementsOf(backtest.trades.map { Triple(it.symbol, it.side, it.timestamp) })
    }
}
