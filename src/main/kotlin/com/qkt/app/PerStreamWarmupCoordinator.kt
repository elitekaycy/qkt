package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
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
        val seeded = seedWarmupHistory()
        seedLatestObservations(seeded)
    }

    /**
     * An observation stream (a hub dataset, a macro series) holds a fact, not a price that
     * refreshes every second: the value a strategy must read at the start of a session is the
     * newest record already published, which may be weeks old (the next FOMC date, last month's
     * CPI). Without it the stream reads as undefined until the next record arrives, so a restart
     * or a backtest starting mid-history would act blind for that long. Seed that one record for
     * every declared observation stream the bar warmup did not already fill.
     */
    private fun seedLatestObservations(alreadySeeded: Set<HubKey>) {
        val nowMs = now.toEpochMilli()
        for ((strategyId, strategy) in strategies) {
            if (strategy !is DslCompiledStrategy) continue
            for ((alias, key) in strategy.declaredStreams) {
                if (key in alreadySeeded || SEEDED_PREFIXES.none { key.qktSymbol.startsWith(it) }) continue
                val latest =
                    runCatching {
                        source
                            .bars(
                                key.qktSymbol,
                                TimeWindow.parse(key.timeframe),
                                TimeRange(Instant.ofEpochMilli(nowMs - OBSERVATION_LOOKBACK_MS), now),
                            ).lastOrNull { it.startTime < nowMs }
                    }.onFailure {
                        log.warn(
                            "warmup: no latest observation for alias={} symbol={}: {}",
                            alias,
                            key.qktSymbol,
                            it.message,
                        )
                    }.getOrNull() ?: continue
                hub.register(key, maxOf(strategy.retentionByKey[key] ?: 1, 1), strategyId)
                hub.seed(key, listOf(latest))
                log.info(
                    "warmup: seeded latest observation strategy={} alias={} symbol={} known_at={}",
                    strategyId,
                    alias,
                    key.qktSymbol,
                    Instant.ofEpochMilli(latest.startTime),
                )
            }
        }
    }

    private fun seedWarmupHistory(): Set<HubKey> {
        val seededKeys = mutableSetOf<HubKey>()
        if (specs.isEmpty()) return seededKeys
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
                val upperMs = warmupUpperMs(key.qktSymbol, bars.window, now.toEpochMilli())
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
                seededKeys += key
                formingBar(key.qktSymbol, bars.window)?.let { forming ->
                    hub.seedForming(key, forming.partial)
                    log.info(
                        "warmup: seeded forming bar strategy={} alias={} minutes={}",
                        strategyId,
                        alias,
                        forming.minutes.size,
                    )
                }
                log.info(
                    "warmup: seeded hub for strategy={} alias={} symbol={} bars={}",
                    strategyId,
                    alias,
                    key.qktSymbol,
                    candles.size,
                )
            }
        }
        return seededKeys
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

    /** Best effort: a source that cannot serve the elapsed minutes leaves the bar to the live ticks, as before. */
    private fun formingBar(
        symbol: String,
        window: TimeWindow,
    ): FormingBar? =
        runCatching { history.forming(symbol, window, now.toEpochMilli()) }
            .onFailure { log.warn("warmup: no forming bar for {} {}: {}", symbol, window.canonicalSpec(), it.message) }
            .getOrNull()

    private fun stream(key: HubKey): WarmupStream = WarmupStream(key.qktSymbol, TimeWindow.parse(key.timeframe))

    private companion object {
        /** How far back to look for the newest observation: covers quarterly and monthly releases. */
        const val OBSERVATION_LOOKBACK_MS: Long = 400L * 24 * 60 * 60 * 1000

        /**
         * Stored observations with a history a source can serve. Chain, open-interest and depth
         * snapshots are observations too, but they are polled live and refresh within seconds.
         */
        val SEEDED_PREFIXES = listOf("HUB:", "MACRO:")
    }
}
