package com.qkt.risk

/**
 * One strategy's halt as the risk state holds it: why, how long it lasts, the UTC day it tripped
 * (a DAILY halt lifts when that day ends) and the moment it tripped, e.g.
 * ("LossStreakHalt[gold]: 3 consecutive losses, max 3", DAILY, 20717, 1789950000000).
 */
internal data class HaltInfo(
    val reason: String,
    val scope: HaltScope,
    val epochDay: Long,
    val haltedAtMs: Long,
)
