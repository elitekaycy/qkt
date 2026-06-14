package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.BarTickFeed
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MacroMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.marketdata.store.DataStore
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.risk.RiskRule
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal

class Backtest(
    private val strategies: List<Pair<String, Strategy>>,
    private val rules: List<RiskRule> = emptyList(),
    /** Account-protection halts; build via [com.qkt.risk.HaltRules.standard] for live parity. */
    private val haltRules: List<com.qkt.risk.HaltRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
    private val source: MarketSource = NullMarketSource,
    private val calendar: TradingCalendar = TradingCalendar.crypto(),
    private val warmupSpec: WarmupSpec = WarmupSpec.None,
    private val symbols: List<String> = emptyList(),
    cadence: SampleCadence? = null,
    private val startingBalance: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    private val instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
    private val brokerKind: BrokerKind = BrokerKind.PAPER,
    /** See [com.qkt.app.TradingPipeline.latencyEnabled]; defaults to the env-var read. */
    private val latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
) {
    private val cadence: SampleCadence =
        cadence
            ?: if (candleWindow != null) SampleCadence.CANDLE_CLOSE else SampleCadence.TICK

    init {
        require(this.cadence != SampleCadence.CANDLE_CLOSE || candleWindow != null) {
            "SampleCadence.CANDLE_CLOSE requires candleWindow"
        }
    }

    constructor(
        strategies: List<Pair<String, Strategy>>,
        rules: List<RiskRule> = emptyList(),
        haltRules: List<com.qkt.risk.HaltRule> = emptyList(),
        ticks: List<Tick>,
        candleWindow: TimeWindow? = null,
        initialTimestamp: Long = 0L,
        cadence: SampleCadence? = null,
        startingBalance: java.math.BigDecimal = java.math.BigDecimal.ZERO,
        instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
        brokerKind: BrokerKind = BrokerKind.PAPER,
        latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
    ) : this(
        strategies = strategies,
        rules = rules,
        haltRules = haltRules,
        feed = HistoricalTickFeed(ticks),
        candleWindow = candleWindow,
        initialTimestamp = initialTimestamp,
        cadence = cadence,
        startingBalance = startingBalance,
        instruments = instruments,
        brokerKind = brokerKind,
        latencyEnabled = latencyEnabled,
    )

    fun run(): BacktestResult =
        com.qkt.research
            .ReplayEngine(
                strategies = strategies,
                rules = rules,
                haltRules = haltRules,
                feed = feed,
                candleWindow = candleWindow,
                initialTimestamp = initialTimestamp,
                source = source,
                calendar = calendar,
                warmupSpec = warmupSpec,
                symbols = symbols,
                cadence = cadence,
                startingBalance = startingBalance,
                instruments = instruments,
                brokerKind = brokerKind,
                latencyEnabled = latencyEnabled,
            ).runToEnd()

    companion object {
        fun fromStore(
            strategies: List<Pair<String, Strategy>>,
            rules: List<RiskRule> = emptyList(),
            haltRules: List<com.qkt.risk.HaltRule> = emptyList(),
            calendar: TradingCalendar = TradingCalendar.crypto(),
            store: DataStore,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
            cadence: SampleCadence? = null,
            startingBalance: BigDecimal = BigDecimal.ZERO,
            instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
            /**
             * Phase 25A: optional pre-fetched bar store (populated by `qkt fetch`). When
             * present, `LocalMarketSource.bars()` reads from it instead of aggregating
             * from ticks for any day fully covered. Falls back to ticks for missing days.
             */
            barStore: com.qkt.marketdata.store.LocalBarStore? = null,
            brokerKind: BrokerKind = BrokerKind.PAPER,
        ): Backtest {
            val (from, to) = store.resolveRange(request)
            val resolved = MarketRequest(symbols = request.symbols, from = from, to = to)
            val localSource = LocalMarketSource(store, FixedClock(time = to.toEpochMilli()), barStore = barStore)
            // MACRO: streams (daily yields/real rates) read from the macro store via a point-in-time
            // source; everything else falls through to the tick store. Non-MACRO runs are unchanged.
            val source: MarketSource =
                if (request.symbols.any { it.startsWith("MACRO:") }) {
                    CompositeMarketSource(
                        routes =
                            listOf(
                                SymbolPattern.prefix("MACRO:") to MacroMarketSource(MacroSeriesStore(store.root)),
                            ),
                        fallback = localSource,
                    )
                } else {
                    localSource
                }
            return fromSource(
                strategies = strategies,
                rules = rules,
                haltRules = haltRules,
                calendar = calendar,
                source = source,
                request = resolved,
                candleWindow = candleWindow,
                cadence = cadence,
                startingBalance = startingBalance,
                instruments = instruments,
                brokerKind = brokerKind,
            )
        }

        fun fromSource(
            strategies: List<Pair<String, Strategy>>,
            rules: List<RiskRule> = emptyList(),
            haltRules: List<com.qkt.risk.HaltRule> = emptyList(),
            calendar: TradingCalendar = TradingCalendar.crypto(),
            source: MarketSource,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
            warmupSpec: WarmupSpec = WarmupSpec.None,
            cadence: SampleCadence? = null,
            startingBalance: BigDecimal = BigDecimal.ZERO,
            instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
            brokerKind: BrokerKind = BrokerKind.PAPER,
        ): Backtest {
            require(
                MarketSourceCapability.TICKS in source.capabilities ||
                    MarketSourceCapability.BARS in source.capabilities,
            ) {
                "Backtest requires a MarketSource with TICKS or BARS; ${source.name} has ${source.capabilities}"
            }
            val from = request.from ?: error("Backtest.fromSource requires explicit MarketRequest.from")
            val to = request.to ?: error("Backtest.fromSource requires explicit MarketRequest.to")
            val range = TimeRange(from, to)
            val perSymbolFeeds: List<TickFeed> =
                request.symbols.map { sym -> replayFeed(source, sym, range, candleWindow) }
            val feed: TickFeed =
                if (perSymbolFeeds.size == 1) perSymbolFeeds[0] else MergingTickFeed(perSymbolFeeds)
            return Backtest(
                strategies = strategies,
                rules = rules,
                haltRules = haltRules,
                calendar = calendar,
                feed = feed,
                candleWindow = candleWindow,
                initialTimestamp = from.toEpochMilli(),
                source = source,
                warmupSpec = warmupSpec,
                symbols = request.symbols,
                cadence = cadence,
                startingBalance = startingBalance,
                instruments = instruments,
                brokerKind = brokerKind,
            )
        }

        /**
         * Picks the replay feed for one symbol: real recorded ticks when the source has them,
         * otherwise synthesized O->L->H->C ticks from its OHLC bars (the only path for bars-only
         * venues like crypto). Preferring ticks keeps tick-sourced backtests (e.g. MT5) byte-for-byte
         * unchanged; the bar fallback is what makes a `qkt fetch`ed crypto symbol backtest at all.
         */
        private fun replayFeed(
            source: MarketSource,
            symbol: String,
            range: TimeRange,
            window: TimeWindow?,
        ): TickFeed {
            val caps = source.capabilities
            val ticksAvailable = MarketSourceCapability.TICKS in caps
            if (ticksAvailable) {
                val iter = source.ticks(symbol, range).iterator()
                if (iter.hasNext()) {
                    val first = iter.next()
                    return SequenceTickFeed(sequenceOf(first) + iter.asSequence())
                }
            }
            // No recorded ticks for this symbol/range. Synthesize from OHLC bars when we can.
            if (MarketSourceCapability.BARS in caps && window != null) {
                return BarTickFeed(source.bars(symbol, window, range))
            }
            // Can't synthesize. A tick-capable source with an empty range is a legitimate gap
            // (e.g. a market-closed day) — yield nothing. A bars-only source reaching here means
            // no candle window was supplied, which is a real misconfiguration.
            require(ticksAvailable) {
                "bar-based backtest for $symbol needs a candle window (timeframe) — pass candleWindow"
            }
            return SequenceTickFeed(emptySequence())
        }
    }
}
