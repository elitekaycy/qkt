package com.qkt.dsl.compile

import com.qkt.derivatives.futures.ActiveContracts
import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicReference

/**
 * Compiles the futures stream fields: `contract` (the followed contract's code, a string), `dte`
 * (days to its expiry) and `days_to_roll` (days to the stream's next roll; a listed contract's is
 * its expiry), as of evaluation time, in days to six decimals. `Undefined` on any stream that is
 * not futures, and once a stream has no contract.
 */
internal object FuturesFieldCompiler {
    /** The fields this compiler owns. */
    val fields: Set<String> = DslVocabulary.contractFields.toSet()

    private val day = BigDecimal(86_400_000)

    fun compile(ref: StreamFieldRef): CompiledExpr {
        val resolver = AtomicReference<ActiveContracts?>()
        return CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val instruments = ctx.strategyContext.instruments
            val contracts =
                resolver.get()?.takeIf { it.instruments === instruments }
                    ?: ActiveContracts(instruments).also(resolver::set)
            val now = ctx.nowMs()
            val active = contracts.at(key.qktSymbol, now) ?: return@CompiledExpr Value.Undefined
            when (ref.field) {
                "contract" -> Value.Str(active.code)
                "dte" -> Value.Num(days(active.expiryMs - now))
                "days_to_roll" -> Value.Num(days(active.nextRollMs - now))
                else -> error("unreachable: ${ref.field}")
            }
        }
    }

    private fun days(ms: Long): BigDecimal = BigDecimal(ms).divide(day, 6, RoundingMode.HALF_EVEN)
}
