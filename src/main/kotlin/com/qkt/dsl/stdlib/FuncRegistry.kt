package com.qkt.dsl.stdlib

import com.qkt.common.Money
import java.math.BigDecimal
import kotlin.math.ln
import kotlin.math.sqrt

private enum class Arity { UNARY, VARIADIC2 }

private data class FuncSpec(
    val name: String,
    val arity: Arity,
    val apply: (List<BigDecimal>) -> BigDecimal,
)

object FuncRegistry {
    private val table: Map<String, FuncSpec> =
        mapOf(
            "ABS" to FuncSpec("ABS", Arity.UNARY) { args -> args[0].abs() },
            "SQRT" to
                FuncSpec("SQRT", Arity.UNARY) { args ->
                    BigDecimal(sqrt(args[0].toDouble())).round(Money.CONTEXT)
                },
            "LOG" to
                FuncSpec("LOG", Arity.UNARY) { args ->
                    BigDecimal(ln(args[0].toDouble())).round(Money.CONTEXT)
                },
            "MIN" to FuncSpec("MIN", Arity.VARIADIC2) { args -> args.reduce { a, b -> a.min(b) } },
            "MAX" to FuncSpec("MAX", Arity.VARIADIC2) { args -> args.reduce { a, b -> a.max(b) } },
        )

    fun has(name: String): Boolean = table.containsKey(name)

    fun invoke(
        name: String,
        args: List<BigDecimal>,
    ): BigDecimal {
        val spec = table[name] ?: error("Unknown function: $name")
        when (spec.arity) {
            Arity.UNARY -> require(args.size == 1) { "$name expects 1 arg, got ${args.size}" }
            Arity.VARIADIC2 -> require(args.size >= 2) { "$name expects >= 2 args, got ${args.size}" }
        }
        return spec.apply(args)
    }
}
