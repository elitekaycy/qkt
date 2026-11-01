package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.StreamStopRelease

/** The operator's way to lift the stops failed rolls left on a session's continuous streams. */
interface StreamStopControl {
    /**
     * Clears each stop of this session's strategies that is safe to clear, and says what became of every one. Only an
     * operator's resume calls this; [LiveSessionHandle.resume] alone, which the risk layers also use, leaves stops be.
     */
    fun releaseStreamStops(): List<StreamStopRelease> = emptyList()
}

/** [StreamStopControl.releaseStreamStops] on the engine thread; an engine that does not answer leaves stops unjudged. */
internal fun releaseStreamStops(
    strategyIds: List<String>,
    broker: Broker,
    snapshot: EngineSnapshot,
): List<StreamStopRelease> =
    strategyIds.flatMap { id ->
        try {
            snapshot.engineSnapshot { broker.releaseStreamStops(id) }
        } catch (e: IllegalStateException) {
            listOf(StreamStopRelease(id, "unknown stream", "unknown", "could not be checked: ${e.message}"))
        }
    }
