package com.qkt.marketdata.hub

/**
 * How much less than the hub a consumer is willing to believe.
 *
 * Defaults match the hub's own: no extra lag, derived records allowed. A production book that has
 * not yet audited a backfill sets `refuseDerived` and sees only what the hub actually observed.
 */
data class HubPolicy(
    val minLagMs: Long = 0L,
    val refuseDerived: Boolean = false,
) {
    init {
        require(minLagMs >= 0) { "HubPolicy.minLagMs must not be negative, got $minLagMs" }
    }
}
