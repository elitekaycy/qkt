package com.qkt.connector.mt5

import com.qkt.common.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Tickets qkt is closing, or has just closed, itself. The position poller asks [state] before
 * publishing a close so it does not emit a second (duplicate) close when it sees one of these
 * tickets gone, e.g. the engine closes 3258722177, the venue confirms, and the next poll finds the
 * ticket missing: [state] says CONFIRMED and the poller stays quiet. Confirmed markers are reaped
 * after the longer of 3 poll intervals and one HTTP timeout plus a poll interval.
 */
internal class MT5EngineCloseMarkers(
    private val profile: MT5BrokerProfile,
    private val clock: Clock,
) {
    private val recentlyClosedByTicket: MutableMap<Long, EngineCloseMarker> = ConcurrentHashMap()

    private data class EngineCloseMarker(
        val startedAtMs: Long,
        val confirmed: Boolean = false,
        val confirmedAtMs: Long? = null,
    )

    /** Set BEFORE the close is sent: the poller could see the position gone before the reply lands. */
    fun begin(
        ticket: Long,
        closeStartedAtMs: Long,
    ) {
        recentlyClosedByTicket[ticket] = EngineCloseMarker(closeStartedAtMs)
    }

    /** The close did not happen (rejected, or unusable reply): the poller owns this ticket again. */
    fun remove(ticket: Long) {
        recentlyClosedByTicket.remove(ticket)
    }

    /**
     * Returns the close request state for [ticket]. A pending marker is never consumed: if
     * the venue closes first and our request later rejects, the next poll must still publish
     * that venue close. A confirmed marker remains until its bounded TTL so a poll racing between
     * confirmation and fill publication cannot consume the only duplicate-suppression record.
     */
    fun engineCloseState(ticket: Long): EngineCloseState {
        val now = clock.now()
        val ttlMs =
            maxOf(
                profile.pollIntervalMs * MT5BrokerLimits.DISAMBIGUATION_TTL_MULTIPLIER,
                profile.httpTimeoutMs + profile.pollIntervalMs,
            )
        recentlyClosedByTicket.entries.removeIf {
            val confirmedAtMs = it.value.confirmedAtMs
            confirmedAtMs != null && now - confirmedAtMs >= ttlMs
        }
        val marker = recentlyClosedByTicket[ticket] ?: return EngineCloseState.NONE
        if (!marker.confirmed) return EngineCloseState.PENDING
        return EngineCloseState.CONFIRMED
    }

    fun confirmEngineClose(ticket: Long) {
        recentlyClosedByTicket.computeIfPresent(ticket) { _, marker ->
            marker.copy(confirmed = true, confirmedAtMs = clock.now())
        }
    }
}
