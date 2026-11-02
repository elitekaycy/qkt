package com.qkt.broker

/**
 * Opt-in capability for brokers that expose authoritative [Broker.marginLevel] data.
 *
 * Kept separate from [Broker] so adding the capability does not break existing broker
 * implementations compiled against an earlier qkt release.
 */
interface MarginLevelProvider {
    /** Whether margin-level reads are supported for this broker composition. */
    val supportsMarginLevel: Boolean

    /**
     * A margin floor will judge entries here: start keeping the level current, without blocking and without reading
     * it on this thread. A venue with a floor of 0 is never asked, so it never polls for a level no one needs.
     */
    fun warmMarginLevel() = Unit
}
