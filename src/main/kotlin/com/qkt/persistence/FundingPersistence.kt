package com.qkt.persistence

/**
 * What a session has booked of a venue's perpetual funding: records funded before [sinceMs] (its first
 * start) are not its own to book, and [booked] holds each record booked since, by venue id, with the time
 * it was funded at, so a record heard again (a replay, a restart) is never booked twice.
 */
data class PersistedFunding(
    val sinceMs: Long,
    val booked: Map<String, Long>,
)

/**
 * Storage for each session's [PersistedFunding], under its state owner [ownerId] (the strategy that also
 * owns the session's risk state). Kept apart from the rest of [StatePersistor] so the defaults leave
 * persistors that predate funding compiling; a persistor that keeps nothing starts every session afresh.
 */
interface FundingPersistence {
    /** Persist [funding], replacing the last save. */
    fun saveFunding(
        ownerId: String,
        funding: PersistedFunding,
    ) {}

    /** The last funding state saved for [ownerId], or null when none was. */
    fun loadFunding(ownerId: String): PersistedFunding? = null
}
