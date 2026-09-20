package com.qkt.research

import com.qkt.app.IndicatorWarmer
import com.qkt.app.PerStreamWarmupCoordinator
import com.qkt.app.TradingPipeline
import com.qkt.dsl.compile.CandleHub
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import java.time.Instant

/**
 * Indicator warmup for a replay, the same two routes live takes: per-stream history into the
 * [CandleHub] when strategies declare per-stream warmup, otherwise the global [WarmupSpec] replayed
 * through the pipeline. Constructing it prepares the hub; [warm] runs once the pipeline exists.
 */
internal class ReplayWarmup(
    strategies: List<Pair<String, Strategy>>,
    private val source: MarketSource,
    candleHub: CandleHub,
    private val initialTimestamp: Long,
    private val warmupSpec: WarmupSpec,
    private val tradedSymbols: List<String>,
) {
    private val perStreamWarmup =
        if (source !== NullMarketSource) {
            PerStreamWarmupCoordinator(
                strategies = strategies,
                source = source,
                hub = candleHub,
                now = Instant.ofEpochMilli(initialTimestamp),
                requireFullHistory = false,
            ).also { it.prepareHub() }
        } else {
            null
        }

    /** Warm every indicator of [pipeline] from history ending at the replay start. */
    fun warm(pipeline: TradingPipeline) {
        if (perStreamWarmup?.specs?.isNotEmpty() == true) {
            perStreamWarmup.warm(pipeline)
        } else if (source !== NullMarketSource && warmupSpec !is WarmupSpec.None && tradedSymbols.isNotEmpty()) {
            IndicatorWarmer(source, pipeline).warmup(
                symbols = tradedSymbols,
                spec = warmupSpec,
                now = Instant.ofEpochMilli(initialTimestamp),
            )
        }
    }
}
