package com.qkt.cli

import com.qkt.backtest.BacktestDataProvisioner
import com.qkt.backtest.OptionChainCoverage
import com.qkt.backtest.ProvisionStream
import com.qkt.dsl.ast.CHAIN_BROKER
import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.OPTIONS_BROKER
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.optionSymbols
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.dukascopy.DukascopyInstrument
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The market-data provisioning every backtest entry point shares: fetch (unless `noFetch`) and
 * validate the tick days of each replayed symbol that reads the tick store (not MACRO, BYBIT, HUB or
 * option streams, nor streams whose fetched bars already cover the window; `--bars` replays read no
 * ticks at all), then check the stored chain days of option contracts ([OptionChainCoverage]).
 */
internal object BacktestTickProvisioning {
    /** Provisions [replaySymbols] for the window [from]..[to]. */
    fun provision(
        replaySymbols: List<String>,
        instruments: InstrumentRegistry,
        barReplay: BacktestBarReplay.BarReplayConfig,
        stores: Pair<DefaultDataStore, LocalBarStore>,
        window: Pair<Instant, Instant>,
        noFetch: Boolean,
        allowIncomplete: Boolean,
    ) {
        val (store, barStore) = stores
        val provisionFrom = LocalDate.ofInstant(window.first, ZoneOffset.UTC)
        val provisionTo = LocalDate.ofInstant(window.second.minusMillis(1), ZoneOffset.UTC)
        val tickProvisionStreams =
            (replaySymbols - instruments.optionSymbols(replaySymbols))
                .map { BacktestBarReplay.brokerAndBare(it) }
                .filter { (broker, _) ->
                    broker != "MACRO" &&
                        broker != "BYBIT" &&
                        broker != HUB_BROKER &&
                        broker != CHAIN_BROKER &&
                        broker != OPTIONS_BROKER
                }.distinct()
                .map { (broker, bare) -> ProvisionStream(broker = broker, bareSymbol = bare) }
                .filterNot { stream ->
                    val declared = barReplay.finestDeclared["${stream.broker}:${stream.bareSymbol}"]
                    declared != null &&
                        BacktestBarReplay.hasCompleteFetchedBars(barStore, stream, declared, provisionFrom, provisionTo)
                }
        val (fetchableStreams, validateOnlyStreams) =
            tickProvisionStreams.partition { DukascopyInstrument.ofOrNull(it.bareSymbol) != null }
        if (!barReplay.forceBars && !provisionTo.isBefore(provisionFrom) && tickProvisionStreams.isNotEmpty()) {
            for ((streams, fetch) in listOf(fetchableStreams to !noFetch, validateOnlyStreams to false)) {
                BacktestDataProvisioner(store).ensure(
                    streams = streams,
                    from = provisionFrom,
                    to = provisionTo,
                    fetchEnabled = fetch,
                    allowIncomplete = allowIncomplete,
                    calendarFor = { BacktestContext.defaultCalendars().calendarFor(it) },
                )
            }
        }
        OptionChainCoverage.ensure(instruments, replaySymbols, provisionFrom, provisionTo, allowIncomplete)
    }
}
