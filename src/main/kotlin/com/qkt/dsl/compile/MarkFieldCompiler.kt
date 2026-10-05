package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.marks.MarkPrices
import com.qkt.marketdata.source.MarketSource
import java.util.concurrent.atomic.AtomicReference

/**
 * Compiles `<alias>.mark` and `<alias>.index`: the contract's mark and index price as its data source knows
 * them at evaluation time ([MarkPrices]): live, the newest its venue quoted; in a backtest, the newest stored
 * sample before the instant (at a bar close, the last inside the bar). Undefined until a value is known. A
 * source that serves no marks is refused before the strategy starts, so it never reads as a silent Undefined.
 */
internal object MarkFieldCompiler {
    /** The fields this compiler owns. */
    val fields: Set<String> = DslVocabulary.markFields.toSet()

    private class Resolved(
        val source: MarketSource,
        val marks: MarkPrices?,
        val windowMs: Long,
    )

    fun compile(ref: StreamFieldRef): CompiledExpr {
        val resolved = AtomicReference<Resolved?>()
        return CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val source = ctx.strategyContext.source
            val at =
                resolved.get()?.takeIf { it.source === source }
                    ?: Resolved(source, source.marksFor(key.qktSymbol), TimeWindow.parse(key.timeframe).durationMs)
                        .also(resolved::set)
            val sample = at.marks?.at(key.qktSymbol, at.windowMs, ctx.nowMs())
            val value = if (ref.field == "mark") sample?.mark else sample?.index
            if (value == null) Value.Undefined else Value.Num(value)
        }
    }
}
