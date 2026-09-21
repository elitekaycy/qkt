package com.qkt.app

/**
 * Messages drained by the live engine loop's single consumer thread (see [EngineLoop]), e.g. a
 * broker poller's fill arrives as [BusEvent] and an operator flatten as [Flatten].
 */
internal sealed interface Inbound {
    data class FeedTick(
        val tick: com.qkt.marketdata.Tick,
    ) : Inbound

    data class BusEvent(
        val event: com.qkt.events.Event,
    ) : Inbound

    data class Heartbeat(
        val nowMs: Long,
    ) : Inbound

    class Query(
        val execute: () -> Unit,
    ) : Inbound

    object Flatten : Inbound

    object PersistenceHealthCheck : Inbound

    data class FeedEnded(
        val unexpected: Boolean,
        val reason: String,
    ) : Inbound

    data class GracefulStop(
        val deadlineNanos: Long,
    ) : Inbound
}
