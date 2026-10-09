package com.qkt.cli

import com.qkt.backtest.BacktestDataProvisioner
import com.qkt.backtest.OptionChainCoverage
import com.qkt.backtest.ProvisionStream
import com.qkt.dsl.ast.CHAIN_BROKER
import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.OPTIONS_BROKER
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.optionSymbols
import com.qkt.marketdata.depth.BookDepthSymbol
import com.qkt.marketdata.openinterest.OpenInterestSymbol
import com.qkt.marketdata.store.BarCompletenessValidator
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.dukascopy.DukascopyInstrument
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The market-data provisioning every backtest entry point shares: fetch (unless `noFetch`) and
 * validate the tick days of each replayed symbol that reads the tick store (not MACRO, HUB or option
 * streams, nor streams whose fetched bars already cover the window; `--bars` replays read no
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
                        broker != HUB_BROKER &&
                        broker != CHAIN_BROKER &&
                        broker != OpenInterestSymbol.BROKER &&
                        broker != BookDepthSymbol.BROKER &&
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
            val binaryBars = BinaryBarStore(store.root)
            for ((streams, fetch) in listOf(fetchableStreams to !noFetch, validateOnlyStreams to false)) {
                BacktestDataProvisioner(store).ensure(
                    streams = streams,
                    from = provisionFrom,
                    to = provisionTo,
                    fetchEnabled = fetch,
                    allowIncomplete = allowIncomplete,
                    calendarFor = { BacktestContext.defaultCalendars().calendarFor(it) },
                    barsLineFor = { barsLine(binaryBars, barReplay, it, provisionFrom, provisionTo) },
                )
            }
        }
        OptionChainCoverage.ensure(instruments, replaySymbols, provisionFrom, provisionTo, allowIncomplete)
    }

    /**
     * The bars suggestion for the missing-tick error: names the coarsest built timeframe that
     * covers the window, or states none does (with the `build-bars` fix when the strategy's
     * timeframe is known). Reads file presence only; never fetches.
     */
    private fun barsLine(
        binaryBars: BinaryBarStore,
        barReplay: BacktestBarReplay.BarReplayConfig,
        stream: ProvisionStream,
        from: LocalDate,
        to: LocalDate,
    ): String {
        val calendar = BacktestContext.defaultCalendars().calendarFor(stream.bareSymbol)
        val coverages =
            binaryBars
                .builtTimeframes(stream.broker, stream.bareSymbol)
                .sortedByDescending { it.durationMs }
                .map { tf ->
                    tf to
                        BarCompletenessValidator.validate(
                            binaryBars,
                            stream.broker,
                            stream.bareSymbol,
                            tf,
                            from,
                            to,
                            calendar,
                        )
                }
        val full = coverages.firstOrNull { it.second.missingDays.isEmpty() }
        if (full != null) {
            return "Bars exist for this window (${full.first.canonicalSpec()}, " +
                "${full.second.coveredTradingDays} of ${full.second.requestedTradingDays} days): " +
                "rerun with --bars to use them (faster, approximate fills)."
        }
        val declared = barReplay.finestDeclared["${stream.broker}:${stream.bareSymbol}"]?.canonicalSpec()
        val build =
            if (declared !=
                null
            ) {
                " Try qkt data build-bars ${stream.bareSymbol} --tf $declared to build them."
            } else {
                ""
            }
        return "No built bars cover this window.$build"
    }
}
