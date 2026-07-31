package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.marketdata.source.MarketSource
import com.qkt.strategy.PerStreamWarmable
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import com.qkt.strategy.windowMs
import java.time.Instant
import org.slf4j.LoggerFactory

/** Shared live/backtest preparation for exact-stream DSL warmup requirements. */
internal class PerStreamWarmupCoordinator(
    private val strategies: List<Pair<String, Strategy>>,
    private val source: MarketSource,
    private val hub: CandleHub,
    private val now: Instant,
    private val requireFullHistory: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(PerStreamWarmupCoordinator::class.java)
    private val history = WarmupHistoryLoader(source)
    private val unavailableStreams = mutableSetOf<WarmupStream>()

    val specs: Map<WarmupStream, WarmupSpec> =
        strategies
            .map { it.second }
            .filterIsInstance<PerStreamWarmable>()
            .flatMap { it.perStreamWarmup.entries }
            .groupBy(keySelector = { it.key }, valueTransform = { it.value })
            .mapValues { (_, values) -> values.maxBy { it.windowMs(now) } }

    /** Register retention and seed closed history before strategies bind to the hub. */
    fun prepareHub() {
        if (specs.isEmpty()) return
        for ((strategyId, strategy) in strategies) {
            if (strategy !is DslCompiledStrategy) continue
            for ((key, retention) in strategy.retentionByKey) {
                val warmupBars = (specs[stream(key)] as? WarmupSpec.Bars)?.count ?: 0
                hub.register(key, maxOf(retention, warmupBars), strategyId)
            }
            for ((alias, key) in strategy.declaredStreams) {
                val warmupStream = stream(key)
                val spec = specs[warmupStream] ?: continue
                val bars = spec as? WarmupSpec.Bars ?: continue
                val upperMs = bars.window.windowStartFor(now.toEpochMilli())
                val candles =
                    try {
                        if (!requireFullHistory) {
                            history.loadAvailable(key.qktSymbol, bars.window, bars.count, upperMs)
                        } else {
                            history.load(key.qktSymbol, bars.window, bars.count, upperMs)
                        }
                    } catch (error: WarmupUnderfilledException) {
                        throw error
                    } catch (error: Exception) {
                        if (!requireFullHistory) {
                            log.warn(
                                "warmup: no pre-window history for strategy={} alias={} symbol={}: {}",
                                strategyId,
                                alias,
                                key.qktSymbol,
                                error.message,
                            )
                            unavailableStreams.add(warmupStream)
                            emptyList()
                        } else {
                            throw WarmupFailedException(alias, key.qktSymbol, error)
                        }
                    }
                if (candles.isEmpty()) continue
                hub.seed(key, candles)
                log.info(
                    "warmup: seeded hub for strategy={} alias={} symbol={} bars={}",
                    strategyId,
                    alias,
                    key.qktSymbol,
                    candles.size,
                )
            }
        }
    }

    /** Replay the prepared history through indicators without enabling strategy signals. */
    fun warm(pipeline: TradingPipeline) {
        if (specs.isEmpty()) return
        val warmer = IndicatorWarmer(source, pipeline, history)
        if (requireFullHistory) {
            warmer.warmup(specs, now)
            return
        }
        for ((stream, spec) in specs) {
            if (stream in unavailableStreams) continue
            try {
                warmer.warmup(mapOf(stream to spec), now, requireFullHistory = false)
            } catch (error: Exception) {
                log.warn(
                    "warmup: continuing backtest without pre-window history for {}: {}",
                    stream.symbol,
                    error.message,
                )
            }
        }
    }

    private fun stream(key: HubKey): WarmupStream = WarmupStream(key.qktSymbol, TimeWindow.parse(key.timeframe))
}
