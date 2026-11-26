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
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.parity.DslParityState.TradeState
import com.qkt.parity.DslParityState.tradeState
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One strategy with a moving symbol set run two ways over the same [ticks]: as an uninterrupted [backtest], and as a
 * chain of live [session]s that share one state directory under [dir], each started the way a deploy starts
 * ([liveStrategy]: listing, persisted state). The live sessions' membership [journal] and [trades] add up across
 * the chain, so a test compares them with the backtest's.
 */
internal class LiveSetSessions(
    private val dir: Path,
    private val id: String,
    private val source: String,
    private val window: TimeWindow,
    private val ticks: List<Tick>,
) {
    private val balance = BigDecimal("10000")

    /** What the venue lists now; a test adds a contract when the venue starts listing it. */
    val listed = CopyOnWriteArrayList<InstrumentMeta>()
    val journal = mutableListOf<Line>()
    val trades = mutableListOf<TradeState>()

    val catalog: SetCatalog =
        object : SetCatalog {
            override val source = "the venue listing"
            override val survivorsOnly = false

            override fun venue(
                alias: String,
                spec: SymbolSetSpec.Filtered,
            ) = VenueCatalog(listed.toList())
        }

    /** A paper account that keeps no positions across sessions: the persisted books stand, as on a netting venue. */
    private val account: BrokerFactory = { bus, clock, prices, _, _ ->
        object : Broker by PaperBroker(bus, clock, prices) {
            override fun isAccountWide(symbol: String) = true
        }
    }

    /** The journal and trades of one backtest from [fromMs] to [toMs] over a catalog of [rows]. */
    fun backtest(
        rows: List<InstrumentMeta>,
        fromMs: Long,
        toMs: Long,
    ): Pair<List<Line>, List<TradeState>> {
        val lines = mutableListOf<Line>()
        val ast = DynamicSetFixtures.ast(source)
        val catalog = SymbolSetResolution.FileCatalog(rows)
        val plan = requireNotNull(DynamicSetRuns.plan(ast, catalog, fromMs, toMs, false) {})
        val result =
            Backtest(
                strategies = listOf(id to plan.strategy(emptyMap()) { lines += DynamicSetFixtures.line(it) }),
                ticks = ticks,
                candleWindow = window,
                initialTimestamp = fromMs,
                startingBalance = balance,
                startingBalances = mapOf(id to balance),
            ).run()
        return lines to result.trades.map { tradeState(it.strategyId, it.trade, it.realized) }
    }

    /** One live session over the ticks in [fromMs, toMs); [venue] builds its market source from its host and tape. */
    fun session(
        fromMs: Long,
        toMs: Long,
        venue: (DynamicSetHost, List<Tick>) -> MarketSource,
    ) {
        val file = dir.resolve("$id.qkt").also { Files.writeString(it, source) }
        val persistor = FileStatePersistor(dir.resolve("state"))
        val (ast, strategy) = liveStrategy(file, catalog, fromMs, persistor)
        val host = strategy as DynamicSetHost
        val clock = FixedClock(fromMs)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<SetMembershipChanged> { journal += DynamicSetFixtures.line(it) }
        val handle =
            LiveSession(
                strategies = listOf(id to host),
                haltRules = emptyList(),
                source = venue(host, ticks.filter { it.timestamp in fromMs until toMs }),
                symbols = ast.streams.map { it.qktSymbol },
                candleWindow = window,
                clock = clock,
                calendar = TradingCalendar.crypto(),
                onTrade = { trade, realized, owner -> trades += tradeState(owner, trade, realized) },
                initialBalance = balance,
                startingBalances = mapOf(id to balance),
                brokerFactories = mapOf("backtest" to account),
                persistor = persistor,
                busOverride = bus,
            ).start()
        check(handle.awaitTermination(Duration.ofSeconds(30))) { "live session did not terminate" }
    }
}
