package com.qkt.broker.continuous

/** What a roll left behind: strategies [stopped] on the stream (with the reason) and those it [flattened]. */
internal data class RollOutcome(
    val stopped: Map<String, String>,
    val flattened: Set<String>,
)
