package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.backtest.ProvisionStream
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Bar-replay planning for a backtest: which symbols replay from built bars, at what timeframe, and whether the stored bars cover the window. */
internal object BacktestBarReplay {
    /** Split a qktSymbol into (broker, bare): "MT5:EURUSD" -> ("MT5","EURUSD"), "EURUSD" -> ("BACKTEST","EURUSD"). */
    fun brokerAndBare(qktSymbol: String): Pair<String, String> {
        val parts = qktSymbol.split(":", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "BACKTEST" to qktSymbol
    }

    fun hasCompleteFetchedBars(
        barStore: LocalBarStore,
        stream: ProvisionStream,
        window: TimeWindow,
        from: LocalDate,
        to: LocalDate,
    ): Boolean {
        // Bars at the window's own timeframe, or at a finer stored one the reader rolls up from.
        val finer = barStore.finerTimeframe(stream.broker, stream.bareSymbol, window)
        return listOfNotNull(window, finer).any { tf ->
            var day = from
            while (!day.isAfter(to)) {
                if (!barStore.hasDay(stream.broker, stream.bareSymbol, tf.canonicalSpec(), day)) return@any false
                day = day.plusDays(1)
            }
            true
        }
    }

    data class BarReplayConfig(
        val forceBars: Boolean,
        val tickFills: Boolean,
        val binaryBarStore: BinaryBarStore,
        val finestDeclared: Map<String, TimeWindow>,
        val barWindows: Map<String, TimeWindow>,
    )

    /**
     * Resolve bar-replay parameters for a backtest. Validates --bars/--tick-fills constraints,
     * chooses the coarsest built timeframe that divides each declared timeframe, and checks
     * bar coverage so portfolio and single-strategy backtests fail identically.
     */
    fun resolveBarReplay(
        args: Args,
        dataRoot: String,
        from: Instant,
        to: Instant,
        symbols: List<String>,
        streams: List<com.qkt.dsl.ast.StreamDecl>,
        candleWindow: TimeWindow?,
        executionConfig: ExecutionSimulationConfig,
    ): BarReplayConfig {
        val forceBars = args.flag("bars")
        val explicit = args.flag("tick-fills")
        require(!explicit || forceBars) {
            "--tick-fills requires --bars (bars drive signals; ticks resolve fills)"
        }
        val blocker = tickFillsBlocker(symbols, streams, executionConfig)
        require(!explicit || blocker == null) { "--tick-fills is not valid here: $blocker" }
        // `qkt backtest --bars` resolves fills on real ticks by default wherever that is valid; a bar without
        // ticks fills at its levels, as bar fills do (#1439). `--no-tick-fills` keeps bar fills everywhere.
        val byDefault = forceBars && !explicit && !args.flag("no-tick-fills") && args.subcommand == "backtest"
        if (byDefault && blocker != null) {
            System.err.println("qkt: note: tick-resolved fills are off for this run: $blocker")
        }
        val tickFills = explicit || (byDefault && blocker == null)
        require(!forceBars || tickFills || executionConfig.brokerKind != BrokerKind.MT5_SIM) {
            "--bars with --broker mt5-sim is unsafe: synthetic bar extremes do not preserve " +
                "MT5 trigger prices or market spread. Use --bars --tick-fills or full tick replay"
        }
        val binaryBarStore = BinaryBarStore(Paths.get(dataRoot))
        val barTfOverride = args.option("bar-tf")?.let { TimeWindow.parse(it) }
        val fromDay = LocalDate.ofInstant(from, ZoneOffset.UTC)
        val toDay = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)

        fun coverageOf(
            broker: String,
            bare: String,
            tf: TimeWindow,
        ) = com.qkt.marketdata.store.BarCompletenessValidator.validate(
            binaryBarStore,
            broker,
            bare,
            tf,
            fromDay,
            toDay,
            BacktestContext.defaultCalendars().calendarFor(bare),
            LocalBarStore(Paths.get(dataRoot)),
        )
        val finestDeclared: Map<String, TimeWindow> =
            streams
                .filter { it.qktSymbol in symbols }
                .groupBy { it.qktSymbol }
                .mapNotNull { (symbol, symbolStreams) ->
                    symbolStreams
                        .mapNotNull { it.timeframe?.let(TimeWindow::parse) }
                        .minByOrNull { it.durationMs }
                        ?.let { symbol to it }
                }.toMap()
        val barWindows: Map<String, TimeWindow> =
            if (!forceBars) {
                finestReplayWindows(finestDeclared, binaryBarStore, ::coverageOf)
            } else {
                finestDeclared.mapValues { (sym, declared) ->
                    val (broker, bare) = brokerAndBare(sym)
                    if (barTfOverride != null) {
                        require(declared.durationMs % barTfOverride.durationMs == 0L) {
                            "--bar-tf ${barTfOverride.canonicalSpec()} must divide $sym's " +
                                "declared ${declared.canonicalSpec()}"
                        }
                        barTfOverride
                    } else {
                        // Coarsest first, but a folder only counts when it covers the window: a
                        // partial 4h build must not shadow complete 30m bars (#1275). When none
                        // covers it, the coarsest is kept so the guardrail below reports its gaps.
                        val candidates =
                            binaryBarStore
                                .builtTimeframes(broker, bare)
                                .filter { declared.durationMs % it.durationMs == 0L }
                                .sortedByDescending { it.durationMs }
                        var chosen: TimeWindow? = null
                        for (tf in candidates) {
                            val coverage = coverageOf(broker, bare, tf)
                            if (coverage.missingDays.isEmpty()) {
                                chosen = tf
                                break
                            }
                            System.err.println(
                                "qkt: --bars $sym: built ${tf.canonicalSpec()} covers " +
                                    "${coverage.coveredTradingDays}/${coverage.requestedTradingDays} trading days; " +
                                    "trying a finer built timeframe",
                            )
                        }
                        chosen ?: candidates.firstOrNull() ?: declared
                    }
                }
            }
        if (forceBars) {
            for (sym in symbols) {
                val tf = barWindows[sym] ?: candleWindow ?: continue
                val (broker, bare) = brokerAndBare(sym)
                val coverage = coverageOf(broker, bare, tf)
                System.err.println(
                    "qkt: bar coverage $sym ${coverage.coveredTradingDays}/${coverage.requestedTradingDays} " +
                        "trading days (${tf.canonicalSpec()})",
                )
                if (coverage.missingDays.isNotEmpty()) {
                    val none = coverage.coveredTradingDays == 0
                    val message = missingBarsMessage(sym, tf, coverage, fromDay, toDay)
                    if (!args.flag("allow-incomplete")) {
                        // With nothing stored, --allow-incomplete would run a backtest with no prices at all.
                        throw com.qkt.backtest.IncompleteDataException(
                            if (none) message else "$message\n  re-run with --allow-incomplete to proceed anyway",
                        )
                    }
                    System.err.println("qkt: WARNING — $message")
                }
            }
            System.err.println("qkt: --bars research tier — bar-approximated intrabar fills; not for grading")
        }
        return BarReplayConfig(
            forceBars = forceBars,
            tickFills = tickFills,
            binaryBarStore = binaryBarStore,
            finestDeclared = finestDeclared,
            barWindows = barWindows,
        )
    }
}
