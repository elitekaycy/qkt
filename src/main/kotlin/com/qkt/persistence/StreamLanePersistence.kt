package com.qkt.persistence

/**
 * Storage for continuous futures stream lanes, one record per stream under the session's state owner
 * [ownerId] (the strategy that also owns the session's risk state). Kept apart from the rest of
 * [StatePersistor] so the defaults leave persistors that predate stream lanes compiling.
 *
 * Saves are synchronous, also behind [AsyncStatePersistor]: a lane records an engine order before it
 * submits it, so the record must be durable before the venue can answer.
 */
interface StreamLanePersistence {
    /** Persist [lane], replacing the last save for its stream. */
    fun saveStreamLane(
        ownerId: String,
        lane: PersistedStreamLane,
    ) {}

    /** The last lane saved for [stream], or null when none was. */
    fun loadStreamLane(
        ownerId: String,
        stream: String,
    ): PersistedStreamLane? = null
}
