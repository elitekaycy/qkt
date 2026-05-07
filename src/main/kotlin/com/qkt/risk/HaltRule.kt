package com.qkt.risk

interface HaltRule {
    fun evaluate(riskState: RiskState): HaltDecision
}

sealed class HaltDecision {
    data object Continue : HaltDecision()

    data class Halt(
        val reason: String,
        val strategyId: String? = null,
    ) : HaltDecision()
}
