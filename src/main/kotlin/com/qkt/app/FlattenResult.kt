package com.qkt.app

/** Result returned to an operator after an emergency flatten attempt. */
data class FlattenResult(
    val verifiedFlat: Boolean,
    val remainingTickets: List<String> = emptyList(),
    val detail: String? = null,
)
