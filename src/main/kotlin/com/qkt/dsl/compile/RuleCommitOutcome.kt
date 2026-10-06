package com.qkt.dsl.compile

/** How [CompiledRule.commitFire] sealed a rising edge: consumed, or re-armed to fire again. */
internal enum class RuleCommitOutcome {
    ACCEPTED,
    REARMED,
}
