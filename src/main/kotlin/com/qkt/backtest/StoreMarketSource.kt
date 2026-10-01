package com.qkt.backtest

import com.qkt.common.FixedClock
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.instrument.optionSymbols
import com.qkt.marketdata.hub.HubMarketSource
import com.qkt.marketdata.hub.hubRoot
import com.qkt.marketdata.hub.validateHubStreams
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.ContinuousMarketSource
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MacroMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.OptionChainMarketSource
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.macro.MacroSeriesStore
import java.nio.file.Path
import java.time.Instant

/**
 * The [MarketSource] a store-backed backtest reads: the local tick/bar store, with `MACRO:` and
 * `HUB:` streams routed to their own point-in-time sources only when [symbols] declares one, and
 * futures streams stitched from per-contract data when [instruments] declares futures.
 * Fails before the first tick when a declared hub stream is malformed.
 */
internal fun storeMarketSource(
    store: DataStore,
    symbols: List<String>,
    to: Instant,
    hubStoreRoot: Path?,
    barStore: LocalBarStore?,
    forceBars: Boolean,
    binaryBarStore: BinaryBarStore?,
    instruments: InstrumentRegistry = NoopInstrumentRegistry,
): MarketSource {
    val storeSource =
        LocalMarketSource(
            store,
            FixedClock(time = to.toEpochMilli()),
            barStore = barStore,
            // Only the `--bars` research tier reads the binary bar store; normal runs use
            // ticks (or the fetched CSV bar store for bars-only venues), unchanged.
            binaryBarStore = if (forceBars) binaryBarStore else null,
        )
    // Futures streams (continuous `ROOT@front` or listed contracts) read per-contract data through the
    // continuous source; a run with none builds exactly the source it built before futures existed.
    val futures = instruments.futures()
    val localSource =
        if (futures != null &&
            symbols.any { futures.rootOfContinuous(it) != null || instruments.lookup(it)?.derivative != null }
        ) {
            ContinuousMarketSource(storeSource, ContinuousChains(futures), instruments)
        } else {
            storeSource
        }
    // MACRO: streams (daily yields/real rates) read from the macro store via a point-in-time
    // source, and HUB: streams read a qkt-data-hub store the same way. Both are routed only
    // when a run actually declares one, so a run that binds neither constructs exactly the
    // object graph it constructed before either existed and cannot change behaviour.
    val observationRoutes: List<Pair<SymbolPattern, MarketSource>> =
        buildList {
            // Option contracts replay their roots' stored chains; only a run that names one routes them.
            val options = instruments.optionSymbols(symbols)
            if (options.isNotEmpty()) {
                val chains =
                    requireNotNull(instruments.options()?.dataRoot) { "option symbols have no chain data root" }
                add(SymbolPattern.exactSet(options) to OptionChainMarketSource(chains, instruments))
            }
            if (symbols.any { it.startsWith("MACRO:") }) {
                add(SymbolPattern.prefix("MACRO:") to MacroMarketSource(MacroSeriesStore(store.root)))
            }
            if (symbols.any { it.startsWith(HubMarketSource.PREFIX) }) {
                val root = hubStoreRoot ?: hubRoot(store.root)
                // Fail before the first tick rather than after the report: a mistyped
                // field would otherwise be undefined for the whole run, and the result
                // would read as a strategy that found no setups rather than one that was
                // never able to evaluate its own rule.
                val problems = validateHubStreams(root, symbols)
                require(problems.isEmpty()) { "hub data problems:\n  " + problems.joinToString("\n  ") }
                add(SymbolPattern.prefix(HubMarketSource.PREFIX) to HubMarketSource(root))
            }
        }
    return if (observationRoutes.isNotEmpty()) {
        CompositeMarketSource(routes = observationRoutes, fallback = localSource)
    } else {
        localSource
    }
}
