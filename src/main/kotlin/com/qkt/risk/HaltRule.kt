package com.qkt.risk

/**
 * A condition checked against running risk state on every fill / equity tick. When the
 * condition trips, all subsequent submissions for the affected strategy (or the entire
 * account) are auto-rejected by [RiskEngine] until an operator clears the halt.
 */
interface HaltRule {
    /** Return [HaltDecision.Halt] to trip; [HaltDecision.Continue] to allow trading. */
    fun evaluate(riskState: RiskState): HaltDecision
}

/** Outcome of a [HaltRule] check. */
sealed class HaltDecision {
    /** Rule did not trip; trading continues. */
    data object Continue : HaltDecision()

    /**
     * Halt the named strategy ([strategyId]) — or the whole account when null —
     * with the given operator-facing [reason].
     */
    data class Halt(
        val reason: String,
        val strategyId: String? = null,
    ) : HaltDecision()
}
