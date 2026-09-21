package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerFactory
import com.qkt.broker.CompositeBroker
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionProvider
import com.qkt.strategy.Strategy

/**
 * Builds the brokers one live session routes orders to, and the instrument specs they bring.
 * With no configured factories the session fills on paper; otherwise each `BROKER:` prefix the
 * strategies declare gets its own venue broker, e.g. streams on `EXNESS:XAUUSD` and
 * `BYBIT:BTCUSDT` build two brokers behind one fail-closed [CompositeBroker].
 */
internal class SessionBrokers(
    private val strategies: List<Pair<String, Strategy>>,
    private val symbols: List<String>,
    private val brokerFactories: Map<String, BrokerFactory>,
    private val instrumentRegistry: com.qkt.instrument.InstrumentRegistry?,
) {
    /** Captures the broker instances built by [buildBroker] so the session can ask them for their abilities. */
    private val builtBrokers: MutableList<Broker> = mutableListOf()

    /** The venue brokers built so far; empty for a paper session. */
    val built: List<Broker> get() = builtBrokers

    fun buildBroker(
        paperBroker: PaperBroker,
        bus: EventBus,
        clock: Clock,
        priceTracker: MarketPriceTracker,
        positions: PositionProvider,
    ): Broker {
        if (brokerFactories.isEmpty()) return paperBroker
        val dslStrategies =
            strategies.mapNotNull { (_, s) -> s as? com.qkt.dsl.compile.DslCompiledStrategy }
        val brokerSymbols = mutableMapOf<String, MutableSet<String>>()
        for (s in dslStrategies) {
            for (key in s.declaredStreams.values) {
                brokerSymbols
                    .getOrPut(key.broker.lowercase()) { mutableSetOf() }
                    .add(key.qktSymbol)
            }
        }
        // Hand-written strategies (e.g. bot run-session bridges) declare no DSL streams;
        // with factories configured, route by the session's BROKER:SYMBOL prefixes instead
        // of silently paper-filling (the same #139 failure mode, one layer up).
        if (brokerSymbols.isEmpty()) {
            for (sym in symbols) {
                val label = sym.substringBefore(':', "").lowercase()
                if (label.isNotEmpty()) brokerSymbols.getOrPut(label) { mutableSetOf() }.add(sym)
            }
        }
        if (brokerSymbols.isEmpty()) return paperBroker
        // Fail fast if a strategy declares a broker prefix that has no configured factory.
        // Without this check, the old code path silently fell through to `paperBroker` for
        // unmapped prefixes — strategy fills happened on paper instead of the intended venue
        // and operators only noticed when they couldn't find real fills (#139).
        val missing = brokerSymbols.keys.filter { it !in brokerFactories }
        require(missing.isEmpty()) {
            val configuredList = brokerFactories.keys.sorted().joinToString(", ")
            val missingList = missing.sorted().joinToString(", ")
            "Strategy declares broker prefix(es) with no configured factory: [$missingList]. " +
                "Configured brokers: [$configuredList]. " +
                "Either fix the strategy's SYMBOLS prefix or add a `type: mt5` entry " +
                "in qkt.config.yaml's brokers block for each missing prefix."
        }
        // Single-strategy sessions (daemon path) propagate the strategy name so MT5 brokers
        // can correlate orphan recovery; multi-strategy sessions (LiveDemo, Main) pass null.
        val owningStrategy = strategies.singleOrNull()?.first
        val routes =
            brokerSymbols.map { (label, syms) ->
                val factory = brokerFactories.getValue(label)
                val instance = factory.invoke(bus, clock, priceTracker, positions, owningStrategy)
                builtBrokers.add(instance)
                com.qkt.marketdata.source.SymbolPattern
                    .exactSet(syms.toSet()) to instance
            }
        // A configured live session must fail closed. Any symbol outside the declared route set
        // is a typo, stale profile, or incomplete deployment — paper-filling it creates a phantom
        // position that exists only inside qkt. Explicit paper sessions returned above still use
        // PaperBroker directly.
        return CompositeBroker(routes = routes, fallback = null, bus = bus)
    }

    /**
     * Build the [com.qkt.instrument.InstrumentRegistry] the trading pipeline uses for SIZING
     * RISK and PaperBroker fill PnL. Layers the venue specs of every broker in the route list that
     * offers them ([com.qkt.broker.InstrumentProvider]) over the configured registry, so a session
     * trading several accounts (#139) gets each venue's own contract specs. Falls back to
     * [com.qkt.instrument.NoopInstrumentRegistry] when nothing provides specs, so paper-only
     * strategies that don't need contract-size-aware math keep working.
     */
    fun buildInstrumentRegistry(): com.qkt.instrument.InstrumentRegistry {
        val venueRegistries =
            builtBrokers
                .filterIsInstance<com.qkt.broker.InstrumentProvider>()
                .map { it.instrumentRegistry() }
        val layers = venueRegistries + listOfNotNull(instrumentRegistry)
        return when (layers.size) {
            0 -> com.qkt.instrument.NoopInstrumentRegistry
            1 -> layers.single()
            else -> com.qkt.instrument.LayeredInstrumentRegistry(layers)
        }
    }
}
