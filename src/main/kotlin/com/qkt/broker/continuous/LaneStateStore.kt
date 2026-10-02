package com.qkt.broker.continuous

import com.qkt.persistence.PersistedStreamLane
import com.qkt.persistence.StreamLanePersistence

/**
 * Where a live session's continuous stream lanes keep their state across restarts: [persistence], under
 * the session's state owner [ownerId]. A backtest has none, and its lanes save nothing.
 */
class LaneStateStore(
    private val persistence: StreamLanePersistence,
    private val ownerId: String,
) {
    /** Saves [lane], replacing the last save for its stream. */
    fun save(lane: PersistedStreamLane) = persistence.saveStreamLane(ownerId, lane)

    /** The lane last saved for [stream], or null when none was. */
    fun load(stream: String): PersistedStreamLane? = persistence.loadStreamLane(ownerId, stream)
}
