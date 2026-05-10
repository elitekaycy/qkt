package com.qkt.events

/**
 * State-change events emitted by [com.qkt.risk.RiskEngine].
 *
 * Halts are sticky: once halted, all signals from the affected scope are vetoed until
 * a [Resumed] event lifts the halt. `strategyId == null` means the halt is global.
 */
sealed interface RiskEvent : Event {
    /** The risk engine halted further trading. [reason] is the rule label that fired. */
    data class Halted(
        val reason: String,
        val strategyId: String?,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : RiskEvent

    /** An operator (or rule that auto-clears) lifted a previous halt. */
    data class Resumed(
        val strategyId: String?,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : RiskEvent
}
