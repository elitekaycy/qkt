package com.qkt.dsl.compile

import com.qkt.derivatives.options.chain.OptionMarks
import com.qkt.derivatives.options.chain.PositionGreeks
import com.qkt.derivatives.options.chain.StructureGreeks
import com.qkt.derivatives.options.chain.usableAt
import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.instrument.OptionContract
import com.qkt.instrument.OptionTerms
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference

/**
 * Compiles an option contract's `<alias>.iv` (its mark implied volatility, in volatility points) and `.delta`,
 * `.gamma`, `.vega`, `.theta`: Black-76 on that mark IV and the quote's own forward at rate 0, per contract
 * (× contract size), vega per volatility point and theta per day, as `POSITION.<structure>` reports them
 * ([StructureGreeks.perUnit]). The quote is the newest its data source knows at evaluation time
 * ([OptionMarks]); Undefined while it is not usable (no positive mark IV yet, older than the root's
 * `maxQuoteAgeMinutes`, or the contract expired). A stream that is not a catalogued option, or a source that
 * serves no option marks, is refused before the strategy starts, so neither reads as a silent Undefined.
 */
internal object OptionFieldCompiler {
    /** The fields this compiler owns. */
    val fields: Set<String> = DslVocabulary.optionFields.toSet()

    private const val MS_PER_MINUTE = 60_000L

    private class Resolved(
        val source: MarketSource,
        val marks: OptionMarks?,
    )

    fun compile(ref: StreamFieldRef): CompiledExpr {
        val resolved = AtomicReference<Resolved?>()
        return CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val symbol = key.qktSymbol
            val source = ctx.strategyContext.source
            val marks =
                (resolved.get()?.takeIf { it.source === source } ?: Resolved(source, source.optionMarksFor(symbol)))
                    .also(resolved::set)
                    .marks
            val instruments = ctx.strategyContext.instruments
            val meta = instruments.lookup(symbol)
            val terms = meta?.derivative as? OptionTerms ?: return@CompiledExpr Value.Undefined
            val maxAgeMs = (instruments.options()?.optionRoot(symbol)?.maxQuoteAgeMinutes ?: 0) * MS_PER_MINUTE
            val now = ctx.nowMs()
            val quote =
                marks?.at(symbol, now)?.takeIf { it.usableAt(now, terms.expiryMs, maxAgeMs) }
                    ?: return@CompiledExpr Value.Undefined
            val iv = requireNotNull(quote.markIv)
            if (ref.field == "iv") return@CompiledExpr Value.Num(iv)
            val contract = OptionContract(symbol, terms.strike, terms.right, terms.expiryMs)
            val unit = StructureGreeks.perUnit(contract, quote.underlying.toDouble(), iv.toDouble(), now)
            Value.Num(BigDecimal.valueOf(pick(ref.field, unit)).multiply(meta.contractSize))
        }
    }

    private fun pick(
        field: String,
        greeks: PositionGreeks,
    ): Double =
        when (field) {
            "delta" -> greeks.delta
            "gamma" -> greeks.gamma
            "vega" -> greeks.vega
            "theta" -> greeks.theta
            else -> error("unreachable: $field")
        }
}
