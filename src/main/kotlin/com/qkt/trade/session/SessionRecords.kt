package com.qkt.trade.session

/** What became of one intent so far. */
data class IntentFate(
    val intentId: String,
    val identity: String,
    /** `queued`, `fired` (an order went to the pipeline) or `skipped` (none could be built). */
    val state: String,
    val orderIds: List<String> = emptyList(),
    val reason: String? = null,
    val atMs: Long? = null,
    /** True once the engine compiled the action (registering any exit hooks): a restart rebuilds these only. */
    val compiled: Boolean = false,
    /** The timestamp of the tick the intent fired (or was skipped) on: where a replay over recorded ticks enters it. */
    val tickMs: Long? = null,
)

/** One open leg of a session identity. */
data class SessionLeg(
    val identity: String,
    val symbol: String,
    val side: String,
    val quantity: java.math.BigDecimal,
    val entry: java.math.BigDecimal,
    val ticket: String?,
    val legId: String,
)
