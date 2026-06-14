package com.qkt.dsl.stdlib

import com.qkt.common.Money
import java.math.BigDecimal
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

private enum class Arity { UNARY, BINARY, VARIADIC2 }

private data class FuncSpec(
    val name: String,
    val arity: Arity,
    val apply: (List<BigDecimal>) -> BigDecimal?,
)

/**
 * Domain errors (e.g. sqrt of negative, log of non-positive, pow underflow) return null
 * from [FuncSpec.apply]. The compiler renders this as [com.qkt.dsl.compile.Value.Undefined]
 * so it composes with IS NULL.
 */
object FuncRegistry {
    private val table: Map<String, FuncSpec> =
        mapOf(
            "ABS" to FuncSpec("ABS", Arity.UNARY) { args -> args[0].abs() },
            "SQRT" to
                FuncSpec("SQRT", Arity.UNARY) { args ->
                    if (args[0].signum() < 0) {
                        null
                    } else {
                        BigDecimal(sqrt(args[0].toDouble())).round(Money.CONTEXT)
                    }
                },
            "LOG" to
                FuncSpec("LOG", Arity.UNARY) { args ->
                    if (args[0].signum() <= 0) {
                        null
                    } else {
                        BigDecimal(ln(args[0].toDouble())).round(Money.CONTEXT)
                    }
                },
            "EXP" to
                FuncSpec("EXP", Arity.UNARY) { args ->
                    val r = exp(args[0].toDouble())
                    if (r.isInfinite() || r.isNaN()) null else BigDecimal(r).round(Money.CONTEXT)
                },
            "POW" to
                FuncSpec("POW", Arity.BINARY) { args ->
                    val r = args[0].toDouble().pow(args[1].toDouble())
                    if (r.isInfinite() || r.isNaN()) null else BigDecimal(r).round(Money.CONTEXT)
                },
            "MIN" to FuncSpec("MIN", Arity.VARIADIC2) { args -> args.reduce { a, b -> a.min(b) } },
            "MAX" to FuncSpec("MAX", Arity.VARIADIC2) { args -> args.reduce { a, b -> a.max(b) } },
        )

    fun has(name: String): Boolean = table.containsKey(name)

    /**
     * Invoke a registered function. Returns null when the function produced a domain
     * error (e.g. sqrt(negative)); the compiler translates that to Value.Undefined.
     */
    fun invoke(
        name: String,
        args: List<BigDecimal>,
    ): BigDecimal? {
        val spec = table[name] ?: error("Unknown function: $name")
        when (spec.arity) {
            Arity.UNARY -> require(args.size == 1) { "$name expects 1 arg, got ${args.size}" }
            Arity.BINARY -> require(args.size == 2) { "$name expects 2 args, got ${args.size}" }
            Arity.VARIADIC2 -> require(args.size >= 2) { "$name expects >= 2 args, got ${args.size}" }
        }
        return spec.apply(args)
    }

    /** Every registered function name (uppercase), for editor tooling (completion, hover). */
    fun names(): Set<String> = table.keys
}
