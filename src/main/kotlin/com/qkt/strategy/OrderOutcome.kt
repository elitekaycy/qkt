package com.qkt.strategy

import com.qkt.common.Side
import com.qkt.execution.ExitReason
import java.math.BigDecimal

/**
 * One thing that happened to a strategy's order, with its facts, handed to the strategy that owns it
 * (`com.qkt.dsl.compile.DslCompiledStrategy.onOrderOutcome`): a bot session identity reports these to its
 * client. A bracket's stop or target fills under the exit order's own id, with its [Filled.exitReason].
 */
sealed interface OrderOutcome {
    val orderId: String
    val timestamp: Long

    data class Filled(
        override val orderId: String,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        val partial: Boolean,
        val exitReason: ExitReason?,
        override val timestamp: Long,
        /** The order this one is a leg of (a bracket's entry, stop or target), when it is one. */
        val parentOrderId: String? = null,
    ) : OrderOutcome

    /** Refused by a risk rule or by the venue; [reason] names which and why. */
    data class Rejected(
        override val orderId: String,
        val reason: String,
        override val timestamp: Long,
    ) : OrderOutcome

    data class Cancelled(
        override val orderId: String,
        val reason: String,
        override val timestamp: Long,
    ) : OrderOutcome
}
