package com.qkt.connector.gateway

import kotlinx.serialization.json.Json

/**
 * The event half of a VGP v1 gateway: the `GET /v1/stream` WebSocket at [baseUrl], authenticated by
 * [apiKey]. New events reach [onEvent] in sequence order; an event whose `seq` was already processed
 * is dropped. A `reset` event, a change of `stream`, or a skipped `seq` (events lost) calls
 * [onReset] with the reason before anything newer is delivered, so the caller resynchronizes from REST.
 * A dropped socket reconnects with `since=<last seq>` after a backoff that starts at
 * [initialBackoffMs] and doubles to [maxBackoffMs]; [onConnection] hears every drop and reconnect.
 */
class GatewayStream(
    baseUrl: String,
    apiKey: String,
    private val onEvent: (WireEvent) -> Unit,
    private val onReset: (String) -> Unit,
    onConnection: (Boolean, String) -> Unit,
    initialBackoffMs: Long = 1_000,
    maxBackoffMs: Long = 30_000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var streamId: String? = null
    private var lastSeq: Long? = null
    private val socket =
        GatewaySocket(
            "stream",
            apiKey,
            { baseUrl + "/v1/stream" + (synchronized(lock) { lastSeq }?.let { "?since=$it" } ?: "") },
            ::receive,
            onConnection,
            initialBackoffMs,
            maxBackoffMs,
        )

    /** Takes [stream] at [seq] as already processed, so [start] replays everything after it. */
    fun anchor(
        stream: String,
        seq: Long,
    ) = synchronized(lock) {
        streamId = stream
        lastSeq = seq
    }

    /** Opens the stream from the last processed event, or live only when none is known. */
    fun start() = socket.start()

    /** Drops the stream for good, at once: no reconnect follows. */
    fun stop() = socket.stop()

    /** Delivers [text]'s event; the stream's position moves only once it was handled, so a failure is resynchronized. */
    private fun receive(text: String) {
        val event = json.decodeFromString(WireEvent.serializer(), text)
        synchronized(lock) {
            val reason =
                when {
                    event.type == "reset" -> "reset"
                    streamId != null && event.stream != streamId -> "stream $streamId -> ${event.stream}"
                    lastSeq != null && event.stream == streamId && event.seq <= lastSeq!! -> return
                    lastSeq != null && event.seq > lastSeq!! + 1 -> "seq $lastSeq -> ${event.seq}"
                    else -> null
                }
            if (reason != null) onReset(reason)
            if (event.type != "reset") onEvent(event)
            streamId = event.stream
            lastSeq = event.seq
        }
    }
}
