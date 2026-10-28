package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.StreamDecl
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.ScriptDataFetcher
import com.qkt.marketdata.store.dukascopy.DukascopyTickFetcher
import com.qkt.marketdata.store.macro.FredSeriesFetcher
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import com.qkt.marketdata.store.macro.PolicyRateSeriesFetcher
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The setup a strategy backtest and a portfolio backtest share, built one way for both: where the data is read and
 * fetched from, which simulated broker fills, the halts the live daemon would apply, and what is provisioned before
 * the run. A portfolio run used to carry its own copy, and its copy never fetched `MACRO:` series.
 */
internal object BacktestSetup {
    /** The run's tick and bar stores at [dataRoot], fetching unless `--no-fetch`. */
    class Stores(
        val ticks: DefaultDataStore,
        val bars: LocalBarStore,
        val noFetch: Boolean,
    )

    /**
     * The stores at [dataRoot]. The legacy `--fetcher dukascopy --fetcher-script <path>` still wins when set over the
     * default Dukascopy fetcher; [fetcherOverride] (tests) wins over both; `--no-fetch` fetches nothing.
     */
    fun stores(
        args: Args,
        dataRoot: String,
        fetcherOverride: DataFetcher?,
    ): Stores {
        val legacyFetcher: DataFetcher? =
            when (val name = args.option("fetcher")) {
                null -> null
                "dukascopy" -> ScriptDataFetcher.dukascopy(Paths.get(args.requireOption("fetcher-script")))
                else -> throw BacktestContext.Companion.SetupError(
                    "unsupported --fetcher '$name' (supported: dukascopy)",
                )
            }
        val noFetch = args.flag("no-fetch")
        val fetcher: DataFetcher? =
            when {
                noFetch -> null
                fetcherOverride != null -> fetcherOverride
                legacyFetcher != null -> legacyFetcher
                else -> DukascopyTickFetcher()
            }
        return Stores(
            DefaultDataStore(root = Paths.get(dataRoot), fetcher = fetcher),
            LocalBarStore(root = Paths.get(dataRoot)),
            noFetch,
        )
    }

    /** The simulated broker `--broker` names: `paper` (the default) or `mt5-sim`. */
    fun brokerKind(args: Args): BrokerKind =
        when (val raw = args.option("broker")) {
            null, "paper" -> BrokerKind.PAPER
            "mt5-sim" -> BrokerKind.MT5_SIM
            else -> throw BacktestContext.Companion.SetupError("unknown --broker '$raw' (valid: paper, mt5-sim)")
        }

    /**
     * Same config-driven halt construction the live daemon uses, so a strategy that would halt live halts at the same
     * point in its backtest.
     */
    fun haltConfig(cfg: Config): HaltConfig =
        HaltConfig(
            maxDailyLoss = cfg.maxDailyLoss,
            maxDrawdownPct = cfg.maxDrawdownPct,
            maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
            totalDdBasis = cfg.totalDdBasis,
            dailyDdBasis = cfg.dailyDdBasis,
        )

    /**
     * What runs before the backtest: the tick days of [replaySymbols] fetched and judged by the calendar each trades on
     * ([calendarFor], given a qkt symbol), then the `MACRO:` series among [streams] fetched from FRED with enough
     * history before the window for warmup (90 calendar days, about 60 business days). Macro fetching is skipped on
     * `--no-fetch`, and a series the store already brackets is not fetched again.
     */
    fun provisioner(
        args: Args,
        dataRoot: String,
        stores: Stores,
        window: Pair<Instant, Instant>,
        replay: Pair<List<String>, BacktestBarReplay.BarReplayConfig>,
        streams: List<StreamDecl>,
        instruments: InstrumentRegistry,
        calendarFor: (String) -> TradingCalendar,
    ): () -> Unit =
        {
            val (from, to) = window
            val (replaySymbols, barReplay) = replay
            BacktestTickProvisioning.provision(
                replaySymbols,
                instruments,
                barReplay,
                stores.ticks to stores.bars,
                from to to,
                stores.noFetch,
                args.flag("allow-incomplete"),
                calendarFor,
            )
            val provisionFrom = LocalDate.ofInstant(from, ZoneOffset.UTC)
            val provisionTo = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)
            val macroStreams = streams.filter { it.qktSymbol in replaySymbols && it.broker == "MACRO" }
            if (macroStreams.isNotEmpty() && !stores.noFetch && !provisionTo.isBefore(provisionFrom)) {
                val macroStore = MacroSeriesStore(Paths.get(dataRoot))
                val fredFetcher = FredSeriesFetcher(macroStore)
                val policyRateFetcher = PolicyRateSeriesFetcher(macroStore)
                val macroFrom = provisionFrom.minusDays(90)
                for (s in macroStreams) {
                    if (!macroStore.hasRange(s.symbol, macroFrom, provisionTo)) {
                        val policySeries = PolicyRateSeries.fromId(s.symbol)
                        if (policySeries == null) {
                            fredFetcher.fetch(s.symbol, macroFrom, provisionTo)
                        } else {
                            policyRateFetcher.fetch(policySeries, macroFrom, provisionTo)
                        }
                    }
                }
            }
        }
}
