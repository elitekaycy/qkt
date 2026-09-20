package com.qkt.dsl.compile

import com.qkt.dsl.ast.SeriesSymbols
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream

/** Symbols whose feed must supply volume because a VWAP/OBV binds to them (#301). */
internal fun volumeRequiringSymbols(
    bindings: IndicatorBinding.Bag,
    streams: Map<String, HubKey>,
): Set<String> =
    bindings.volumeRequiringAliases
        .mapNotNull { alias ->
            val key = streams[alias] ?: return@mapNotNull null
            if (key.broker == SeriesSymbols.BROKER) null else key.qktSymbol
        }.toSet()

/** Bars of history each real stream needs before its rules may fire, keyed by symbol and window. */
internal fun perStreamWarmupSpecs(
    perStreamWarmup: Map<String, Int>,
    streams: Map<String, HubKey>,
): Map<WarmupStream, WarmupSpec> =
    perStreamWarmup
        .mapNotNull { (alias, bars) ->
            val key = streams[alias] ?: return@mapNotNull null
            if (key.broker == SeriesSymbols.BROKER) return@mapNotNull null
            val window =
                com.qkt.candles.TimeWindow
                    .parse(key.timeframe)
            WarmupStream(key.qktSymbol, window) to bars
        }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (stream, counts) ->
            WarmupSpec.Bars(stream.window, counts.max())
        }
