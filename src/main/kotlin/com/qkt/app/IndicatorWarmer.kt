package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.candleToTicks
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Pre-feeds historical ticks through the pipeline before live signal evaluation.
 *
 * Reads the strategy's [WarmupSpec], queries the [MarketSource] for the needed
 * historical window, and pushes those ticks through the pipeline as
 * [com.qkt.events.WarmupTickEvent]s — indicators populate state but the strategy's
 * `onTick` callback is silenced until warmup completes.
 */
class IndicatorWarmer(
    private val source: MarketSource,
    private val pipeline: TradingPipeline,
) {
    private val log = LoggerFactory.getLogger(IndicatorWarmer::class.java)

    fun warmup(
        symbols: List<String>,
        spec: WarmupSpec,
        now: Instant,
    ) {
        val resolved = resolveBarSpec(spec) ?: return
        for (symbol in symbols) warmupSymbol(symbol, resolved, now)
    }

    /**
     * Per-stream form: each exact [WarmupStream] carries its own [WarmupSpec]. Used by DSL
     * strategies that span multiple timeframes — including the same symbol at two windows —
     * where one spec per symbol would overwrite one stream.
     *
     * Symbols mapped to [WarmupSpec.None] are skipped silently. Failures from
     * `source.bars(...)` propagate (callers wrap them in `WarmupFailedException`).
     */
    fun warmup(
        perStream: Map<WarmupStream, WarmupSpec>,
        now: Instant,
    ) {
        for ((stream, spec) in perStream) {
            val resolved = resolveBarSpec(spec) ?: continue
            require(resolved.window == stream.window) {
                "warmup window mismatch: key=${stream.window} spec=${resolved.window} symbol=${stream.symbol}"
            }
            warmupSymbol(stream.symbol, resolved, now)
        }
    }

    private fun warmupSymbol(
        symbol: String,
        bars: BarSpec,
        now: Instant,
    ) {
        val upperMs = bars.window.windowStartFor(now.toEpochMilli())
        val totalMs = bars.window.durationMs * bars.count
        val lowerMs = upperMs - totalMs
        require(upperMs > lowerMs) {
            "warmup range degenerate: lower=$lowerMs upper=$upperMs symbol=$symbol"
        }
        val range = TimeRange(Instant.ofEpochMilli(lowerMs), Instant.ofEpochMilli(upperMs))

        for (candle in source.bars(symbol, bars.window, range)) {
            for (tick in candleToTicks(candle.copy(symbol = symbol))) {
                require(tick.timestamp < now.toEpochMilli()) {
                    "look-ahead bias: warmup tick beyond now=$now, requested to=${Instant.ofEpochMilli(
                        tick.timestamp,
                    )}; symbol=$symbol"
                }
                pipeline.ingestForWarmup(tick)
            }
        }
    }

    private fun resolveBarSpec(spec: WarmupSpec): BarSpec? =
        when (spec) {
            is WarmupSpec.None -> null
            is WarmupSpec.Bars -> BarSpec(spec.window, spec.count)
            is WarmupSpec.Duration -> {
                val count = (spec.duration.toMillis() / spec.window.durationMs).toInt()
                require(count > 0) {
                    "WarmupSpec.Duration too short for window: duration=${spec.duration} window=${spec.window}"
                }
                BarSpec(spec.window, count)
            }
            is WarmupSpec.Ticks -> {
                if (MarketSourceCapability.TICKS in source.capabilities) {
                    log.warn(
                        "WarmupSpec.Ticks honored by tick source not yet wired in 7b; falling back to bars at ONE_MINUTE",
                    )
                }
                val window = TimeWindow.ONE_MINUTE
                val count = (spec.duration.toMillis() / window.durationMs).toInt()
                require(count > 0) {
                    "WarmupSpec.Ticks duration too short to derive bar count: duration=${spec.duration}"
                }
                BarSpec(window, count)
            }
        }

    private data class BarSpec(
        val window: TimeWindow,
        val count: Int,
    )
}
