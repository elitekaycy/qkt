package com.qkt.dsl.compile

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.openinterest.OpenInterestSymbol
import java.math.BigDecimal

/**
 * Compiles `<stream>.<field>`: a candle field (close, high, bid, timestamp, ...) read from the
 * current bar or the candle hub, or an instrument-meta field (tick_size, contract_size, ...)
 * read from the instrument catalog, a futures contract field ([FuturesFieldCompiler]), or a contract's mark or
 * index ([MarkFieldCompiler]).
 */
internal object StreamFieldCompiler {
    private val candleFields: Set<String> = DslVocabulary.candleFields.toSet()
    private val metaFields: Set<String> = DslVocabulary.metaFields.toSet()

    fun compile(ref: StreamFieldRef): CompiledExpr {
        require(ref.field in candleFields || ref.field in metaFields) {
            "Unknown stream field for ${ref.stream}: ${ref.field}"
        }
        // A venue stream's open interest was rewritten into its own stream before compiling
        // (OpenInterestFieldExpansion); one left here is on an alias with none, such as a basket.
        require(ref.field != OpenInterestSymbol.FIELD) {
            "${ref.stream}.${ref.field} reads a venue's published series: only a stream declared BROKER:SYMBOL has one"
        }
        return when (ref.field) {
            in metaFields -> compileMetaField(ref)
            in FuturesFieldCompiler.fields -> FuturesFieldCompiler.compile(ref)
            in MarkFieldCompiler.fields -> MarkFieldCompiler.compile(ref)
            else -> compileCandleField(ref)
        }
    }

    private fun compileCandleField(ref: StreamFieldRef): CompiledExpr =
        CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val candle =
                if (ctx.currentAlias == ref.stream ||
                    (ctx.currentAlias == null && ctx.candle.symbol == key.qktSymbol)
                ) {
                    ctx.candle
                } else {
                    ctx.historyAsOfMs?.let { ctx.hub.latestAtOrBefore(key, it) }
                        ?: ctx.hub.latest(key)
                }
            if (candle == null) {
                Value.Undefined
            } else {
                val fieldValue: BigDecimal? =
                    when (ref.field) {
                        // `value` is the macro-series accessor (MACRO:DGS10.value); the candle hub
                        // closes each published observation immediately as an event candle.
                        "close", "price", "value" -> candle.close
                        "open" -> candle.open
                        "high" -> candle.high
                        "low" -> candle.low
                        "volume" -> candle.volume
                        "bid" -> candle.bid
                        "ask" -> candle.ask
                        "spread" -> candle.spread
                        // Bar start time in epoch milliseconds (#1130), the same instant the
                        // bar-keyed tooling and reports use.
                        "timestamp" -> BigDecimal.valueOf(candle.startTime)
                        else -> error("unreachable")
                    }
                if (fieldValue == null) Value.Undefined else Value.Num(fieldValue)
            }
        }

    private fun compileMetaField(ref: StreamFieldRef): CompiledExpr =
        CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val meta =
                ctx.strategyContext.instruments.lookup(key.qktSymbol)
                    ?: error("InstrumentMeta missing for ${key.qktSymbol} (covered by startup validation)")
            val value =
                when (ref.field) {
                    "tick_size" -> meta.pointSize
                    "contract_size" -> meta.contractSize
                    "volume_step" -> meta.volumeStep
                    "volume_min" -> meta.volumeMin
                    "swap_long_points" -> meta.swapLongPoints
                    "swap_short_points" -> meta.swapShortPoints
                    "tick_value" -> meta.pointSize.multiply(meta.contractSize)
                    "multiplier" -> meta.contractSize
                    else -> error("unreachable: ${ref.field}")
                }
            Value.Num(value)
        }
}
