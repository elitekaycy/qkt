package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal

/** Which bracket child a price is for; decides the side of entry the distance lands on. */
enum class ChildKind { STOP_LOSS, TAKE_PROFIT }

/** A compiled bracket child price, resolved against the entry side and price when the order is built. */
fun interface CompiledChildPrice {
    fun evaluate(
        ec: EvalContext,
        side: Side,
        entry: BigDecimal,
        stopDistance: BigDecimal?,
    ): BigDecimal?
}

/**
 * Output of [ChildPriceResolver.compileStopLoss] — bracket stop loss as either an
 * engine-managed [StopLossSpec.ArmedTrail] (resolved entirely at compile time, no
 * per-tick evaluation needed) or a [Dynamic] that produces a [StopLossSpec.Fixed]
 * at submission time given the entry price.
 */
sealed interface CompiledStopLoss {
    /**
     * Resolved at signal time. Null means the stop cannot be built yet (an operand is
     * undefined during warm-up, or evaluates to a value the stop spec rejects), and the
     * order is skipped like any other undefined bracket price.
     */
    fun interface Dynamic : CompiledStopLoss {
        fun evaluate(
            ec: EvalContext,
            side: Side,
            entry: BigDecimal,
        ): StopLossSpec?
    }

    data class Static(
        val spec: StopLossSpec,
    ) : CompiledStopLoss
}
