package com.qkt.dsl.compile

import com.qkt.dsl.ast.PositionRef
import java.math.BigDecimal

/**
 * Compiles `POSITION.<alias>`: the net quantity held on a stream's symbol, or, for a basket
 * alias, the direction sign shared by every constituent.
 */
internal object PositionRefCompiler {
    fun compile(
        ref: PositionRef,
        baskets: Map<String, List<String>>,
    ): CompiledExpr {
        val constituents = baskets[ref.stream]
        if (constituents != null) {
            return CompiledExpr { ctx -> Value.Num(basketPositionSign(ctx, constituents)) }
        }
        return CompiledExpr { ctx ->
            val symbol = ctx.streams[ref.stream]?.qktSymbol ?: error("Unknown stream alias: ${ref.stream}")
            val qty =
                ctx.strategyContext.positions
                    .positionFor(symbol)
                    ?.quantity ?: BigDecimal.ZERO
            Value.Num(qty)
        }
    }

    /**
     * Unit-normalized position of a basket alias: `+1` when every constituent holds a
     * net-long position, `-1` when every constituent is net-short, `0` otherwise (flat or a
     * mix of sides). Summing quantities across differently-priced symbols is meaningless, so
     * a basket reports direction only — backing the `POSITION.<basket> = 0` "am I flat here"
     * gate the way `POSITION.<stream> = 0` does for one symbol. e.g. a fanned-out
     * `BUY antipodean` leaves aud and nzd both long, so `POSITION.antipodean` is +1.
     */
    private fun basketPositionSign(
        ctx: EvalContext,
        constituents: List<String>,
    ): BigDecimal {
        val signs =
            constituents.map { alias ->
                val symbol = ctx.streams[alias]?.qktSymbol ?: error("Unknown basket constituent alias: $alias")
                (
                    ctx.strategyContext.positions
                        .positionFor(symbol)
                        ?.quantity ?: BigDecimal.ZERO
                ).signum()
            }
        return when {
            signs.all { it > 0 } -> BigDecimal.ONE
            signs.all { it < 0 } -> BigDecimal.ONE.negate()
            else -> BigDecimal.ZERO
        }
    }
}
