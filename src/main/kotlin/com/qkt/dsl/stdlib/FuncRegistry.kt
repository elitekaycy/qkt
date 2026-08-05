package com.qkt.dsl.stdlib

import com.qkt.common.Money
import java.math.BigDecimal
import java.math.RoundingMode
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
            "MOD" to
                FuncSpec("MOD", Arity.BINARY) { args ->
                    // Floored modulo: a - b*floor(a/b). Result carries the divisor's sign, so for a
                    // positive grid step it lands in [0, b) — the distance past the nearest figure.
                    // e.g. MOD(1.2034, 0.0050) = 0.0034. Division by zero is a domain error (null).
                    if (args[1].signum() == 0) {
                        null
                    } else {
                        val q = args[0].divide(args[1], 0, RoundingMode.FLOOR)
                        args[0].subtract(args[1].multiply(q))
                    }
                },
            "ROUND_TO" to
                FuncSpec("ROUND_TO", Arity.BINARY) { args ->
                    // Round x to the nearest multiple of step — the round-number price grid the figure
                    // sits on. e.g. ROUND_TO(2347, 25) = 2350, ROUND_TO(31.27, 0.5) = 31.5. Pair with
                    // MOD (distance past the figure) to fade approaches to a level. Zero step is a domain
                    // error (null).
                    if (args[1].signum() == 0) {
                        null
                    } else {
                        val q = args[0].divide(args[1], 0, Money.ROUNDING)
                        q.multiply(args[1])
                    }
                },
            "FLOOR" to FuncSpec("FLOOR", Arity.UNARY) { args -> args[0].setScale(0, RoundingMode.FLOOR) },
            "CEIL" to FuncSpec("CEIL", Arity.UNARY) { args -> args[0].setScale(0, RoundingMode.CEILING) },
            "ROUND" to FuncSpec("ROUND", Arity.UNARY) { args -> args[0].setScale(0, Money.ROUNDING) },
            "MIN" to FuncSpec("MIN", Arity.VARIADIC2) { args -> args.reduce { a, b -> a.min(b) } },
            "MAX" to FuncSpec("MAX", Arity.VARIADIC2) { args -> args.reduce { a, b -> a.max(b) } },
            // Cross-sectional rank of the FIRST value among all the values, 1 = highest. List one
            // expression per stream to rank a cross-section, e.g. in stream a's rule
            // `rank_of(a.mom, b.mom, c.mom, d.mom) <= 2` is true when a is in the top 2 by momentum.
            // Competition ranking: ties share the top rank (1 + count strictly greater).
            "RANK_OF" to
                FuncSpec("RANK_OF", Arity.VARIADIC2) { args ->
                    val self = args[0]
                    BigDecimal(1 + args.count { it > self })
                },
            // Cross-sectional min-max scale of the FIRST value among all the values, in [0, 1].
            // e.g. normalize(3, 1, 5) = (3 - 1) / (5 - 1) = 0.5. When all values are equal the
            // denominator is zero and the scaled signal is 0 (no preference). Useful as a raw
            // score; for weights that sum to 1 across symmetric calls use softmax.
            "NORMALIZE" to
                FuncSpec("NORMALIZE", Arity.VARIADIC2) { args ->
                    var min = args[0]
                    var max = args[0]
                    for (i in 1 until args.size) {
                        val v = args[i]
                        if (v < min) min = v
                        if (v > max) max = v
                    }
                    if (max == min) {
                        BigDecimal.ZERO
                    } else {
                        args[0].subtract(min).divide(max.subtract(min), Money.CONTEXT)
                    }
                },
            // Cross-sectional softmax weight of the FIRST value among all the values.
            // Returns a value in (0, 1) and the weights across symmetric calls (each stream
            // computes softmax(self, peers...)) sum to 1. Numerically stable via max subtraction.
            "SOFTMAX" to
                FuncSpec("SOFTMAX", Arity.VARIADIC2) { args ->
                    var max = args[0]
                    for (i in 1 until args.size) {
                        val v = args[i]
                        if (v > max) max = v
                    }
                    var sum = 0.0
                    var first = 0.0
                    for (i in args.indices) {
                        val e = kotlin.math.exp(args[i].subtract(max).toDouble())
                        if (i == 0) first = e
                        sum += e
                    }
                    BigDecimal(first / sum).round(Money.CONTEXT)
                },
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
