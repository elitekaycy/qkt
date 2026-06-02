package com.qkt.research

import com.qkt.execution.Trade
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * A single line in the replay tape, captured in the order it occurred during ingest.
 * Rendered by [TapeRenderer]; carries the engine timestamp at which it fired.
 */
sealed interface TapeEvent {
    /** Engine clock (epoch millis) when this event occurred. */
    val timestamp: Long

    /** A strategy emitted a trading intent (before risk/broker). */
    data class SignalEmitted(override val timestamp: Long, val signal: Signal) : TapeEvent

    /** A broker fill landed. [realized] is the realized P&L of this fill. */
    data class Filled(
        override val timestamp: Long,
        val trade: Trade,
        val realized: BigDecimal,
        val strategyId: String,
    ) : TapeEvent

    /** The risk engine vetoed an order. [reason] is the rule label; [symbol] the order's symbol. */
    data class Rejected(override val timestamp: Long, val symbol: String, val reason: String) : TapeEvent
}
