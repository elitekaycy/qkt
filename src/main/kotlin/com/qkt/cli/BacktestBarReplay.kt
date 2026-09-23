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
        var day = from
        val timeframe = window.canonicalSpec()
        while (!day.isAfter(to)) {
            if (!barStore.hasDay(stream.broker, stream.bareSymbol, timeframe, day)) return false
            day = day.plusDays(1)
        }
        return true
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
        val tickFills = args.flag("tick-fills")
        require(!tickFills || forceBars) {
            "--tick-fills requires --bars (bars drive signals; ticks resolve fills)"
        }
        require(!forceBars || tickFills || executionConfig.brokerKind != BrokerKind.MT5_SIM) {
            "--bars with --broker mt5-sim is unsafe: synthetic bar extremes do not preserve " +
                "MT5 trigger prices or market spread. Use --bars --tick-fills or full tick replay"
        }
        require(!tickFills || (executionConfig.latencyMs == 0L && executionConfig.orderSpacingMs == 0L)) {
            "--tick-fills is not valid with execution latency or order spacing: " +
                "filtered ticks cannot preserve delayed-order release timing; use full tick replay"
        }
        require(!tickFills || executionConfig.stopLatencyMs == 0L) {
            "--tick-fills is not valid with stop latency (${executionConfig.stopLatencyMs}ms): " +
                "filtered ticks cannot preserve delayed stop execution; use full tick replay"
        }
        require(
            !tickFills ||
                streams
                    .map { it.timeframe }
                    .distinct()
                    .size <= 1,
        ) {
            "--tick-fills is not valid for mixed-timeframe strategies: a finer-stream close can place " +
                "a cross-symbol order after the other symbol's bar was already resolved"
        }
        val binaryBarStore = BinaryBarStore(Paths.get(dataRoot))
        val barTfOverride = args.option("bar-tf")?.let { TimeWindow.parse(it) }
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
                finestDeclared
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
                        binaryBarStore
                            .builtTimeframes(broker, bare)
                            .filter { declared.durationMs % it.durationMs == 0L }
                            .maxByOrNull { it.durationMs }
                            ?: declared // no usable built tf — let the guardrail below report it
                    }
                }
            }
        if (forceBars) {
            val fromDay = LocalDate.ofInstant(from, ZoneOffset.UTC)
            val toDay = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)
            for (sym in symbols) {
                val tf = barWindows[sym] ?: candleWindow ?: continue
                val (broker, bare) = brokerAndBare(sym)
                val coverage =
                    com.qkt.marketdata.store.BarCompletenessValidator.validate(
                        binaryBarStore,
                        broker,
                        bare,
                        tf,
                        fromDay,
                        toDay,
                        BacktestContext.defaultCalendars().calendarFor(bare),
                    )
                System.err.println(
                    "qkt: bar coverage $sym ${coverage.coveredTradingDays}/${coverage.requestedTradingDays} " +
                        "trading days (${tf.canonicalSpec()})",
                )
                if (coverage.missingDays.isNotEmpty()) {
                    val message =
                        "--bars: incomplete built bars for $sym: ${coverage.coveredTradingDays}/" +
                            "${coverage.requestedTradingDays} trading days; missing " +
                            coverage.missingDays.joinToString(",") +
                            ". Run: qkt data build-bars $bare --tf ${tf.canonicalSpec()} " +
                            "--from $fromDay --to ${toDay.plusDays(1)}"
                    if (!args.flag("allow-incomplete")) {
                        throw com.qkt.backtest.IncompleteDataException(
                            "$message\n  re-run with --allow-incomplete to proceed anyway",
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
