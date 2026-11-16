package com.qkt.connector.gateway

import com.qkt.common.Clock

/**
 * A gateway's instrument listing, read through [read] at most once every [maxAgeMs]: a deploy asks for the spec of
 * each of its symbols in turn, and each ask was a read of the whole listing (megabytes on a venue of thousands of
 * instruments). The gateway itself serves a listing it reads on a timer, so an answer a few seconds old loses
 * nothing. A read that fails is not kept: the next ask reads again.
 */
internal class GatewayListing(
    private val clock: Clock,
    private val maxAgeMs: Long = 5_000,
    private val read: () -> List<WireInstrument>,
) {
    private var held: List<WireInstrument>? = null
    private var readAtMs = 0L

    /** The listing as last read, read again when that was [maxAgeMs] ago or more. */
    @Synchronized
    fun latest(): List<WireInstrument> {
        val now = clock.now()
        held?.takeIf { now - readAtMs < maxAgeMs }?.let { return it }
        return read().also {
            held = it
            readAtMs = now
        }
    }
}
