package com.qkt.cli

import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.dsl.ast.StreamDecl

/**
 * Why tick-resolved fills (`--bars` signals, fills on the real ticks inside each bar) cannot run for these
 * [symbols] and [streams] under [executionConfig], or null when they can. An explicit `--tick-fills` refuses
 * with this reason; the `qkt backtest --bars` default turns them off with it and says so.
 */
internal fun tickFillsBlocker(
    symbols: List<String>,
    streams: List<StreamDecl>,
    executionConfig: ExecutionSimulationConfig,
): String? =
    when {
        symbols.any { '@' in it } -> "continuous futures streams are not supported yet"
        executionConfig.latencyMs != 0L || executionConfig.orderSpacingMs != 0L ->
            "execution latency or order spacing needs full tick replay: filtered ticks cannot preserve delayed releases"
        executionConfig.stopLatencyMs != 0L ->
            "stop latency (${executionConfig.stopLatencyMs}ms) needs full tick replay to preserve delayed stops"
        streams.map { it.timeframe }.distinct().size > 1 ->
            "mixed-timeframe strategies are not supported: a finer-stream close can place a cross-symbol order " +
                "after the other symbol's bar was already resolved"
        else -> null
    }
