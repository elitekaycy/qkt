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

/**
 * Whether a halt clears on its own. [DAILY] halts (daily-loss / daily-drawdown limits) auto-resume
 * at the next UTC midnight, since the limit is measured per UTC day. [PERSISTENT] halts (total /
 * trailing drawdown — cumulative, account-terminating) stay halted until an operator resumes.
 */
enum class HaltScope { DAILY, PERSISTENT }

/** Outcome of a [HaltRule] check. */
sealed class HaltDecision {
    /** Rule did not trip; trading continues. */
    data object Continue : HaltDecision()

    /**
     * Halt the named strategy ([strategyId]) — or the whole account when null —
     * with the given operator-facing [reason]. [scope] controls auto-resume (see [HaltScope]).
     */
    data class Halt(
        val reason: String,
        val strategyId: String? = null,
        val scope: HaltScope = HaltScope.PERSISTENT,
    ) : HaltDecision()
}
