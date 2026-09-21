package com.qkt.connector.mt5

import com.qkt.common.Side
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** An entry order the venue filled only in part: the open position plus the resting residual order. */
internal data class PartialEntryState(
    val meta: MT5TicketMeta,
    val residualTicket: Long,
    val positionTicket: Long,
    val symbol: String,
    val side: Side,
    val requestedQuantity: BigDecimal,
    val cumulativeFilled: BigDecimal,
    val averageFillPrice: BigDecimal,
)

/**
 * The ticket bookkeeping one [MT5Broker] shares between its collaborators: exactly one instance per
 * broker, handed to each of them by reference so placement callbacks, both pollers and restart
 * recovery all read and write the same books. E.g. a placement callback registers pending ticket
 * 3258722177 in [pendingBook]; the position poller later takes it out and tracks it in [positionBook].
 *
 * Nothing here locks on its own. Steps that must change [pendingBook] together with the
 * partial-entry maps or [earlyPositionByTicket] hold [pendingTransitionLock] at the call site.
 */
internal class MT5BrokerState(
    profile: MT5BrokerProfile,
) {
    /**
     * Cached venue symbol metadata, keyed by broker symbol (e.g. `"XAUUSDm"`). Populated
     * lazily on first placement of a symbol via `/symbol_info`; entries never expire
     * within a broker lifetime — the venue's `volume_step` / `volume_min` don't change
     * mid-session for spot instruments. [MT5BrokerProfile.instrumentOverrides] takes
     * precedence over the cache so operators can pin values without the gateway round-trip.
     */
    val symbolMeta: MutableMap<String, MT5SymbolInfo> = ConcurrentHashMap()

    val pendingBook = MT5PendingBook()

    /**
     * Positions observed before the asynchronous placement response registered their pending
     * ticket. This closes the poller-vs-HTTP callback race without treating a venue position as
     * external merely because the callback arrived a few milliseconds later.
     */
    val earlyPositionByTicket: MutableMap<Long, MT5Position> = ConcurrentHashMap()
    val pendingTransitionLock = Any()

    /** Entry orders whose first venue response filled only part of the requested quantity. */
    val partialEntryByPositionTicket: MutableMap<Long, PartialEntryState> = ConcurrentHashMap()
    val partialPositionByResidualTicket: MutableMap<Long, Long> = ConcurrentHashMap()

    /**
     * Open positions opened by this qkt session, keyed by MT5 ticket. Lets
     * [MT5PositionPoller] resolve a closed ticket back to (clientOrderId, strategyId)
     * when it observes the ticket disappear. Populated on synchronous Market/Bracket fills
     * and when a pending order transitions to a position. Entries are removed when the
     * poller publishes the close event.
     */
    val positionBook = MT5PositionBook()
    val venueCostLedger =
        MT5VenueCostLedger(
            closedRetentionMs = maxOf(60_000L, profile.httpTimeoutMs + profile.pollIntervalMs * 2L),
        )

    val expectedProtectionByTicket: MutableMap<Long, MT5PositionProtection> = ConcurrentHashMap()

    /**
     * Tickets that just transitioned from pending → position. The pending-order poller
     * will subsequently see them disappear from `/orders`; this set disambiguates
     * "filled" (already emitted [com.qkt.events.BrokerEvent.OrderFilled]) from "external cancel."
     * Entries expire after [MT5BrokerLimits.DISAMBIGUATION_TTL_MULTIPLIER] × the poll interval.
     */
    val recentlyFilledTickets: MutableMap<Long, Long> = ConcurrentHashMap()
}

/** Retry and expiry bounds the broker's collaborators share. */
internal object MT5BrokerLimits {
    /**
     * Multiplier applied to [MT5BrokerProfile.pollIntervalMs] for the
     * fill-vs-cancel disambiguation TTL. 3 cycles is enough headroom for the
     * position poller to tick at least once after the pending poller does.
     */
    const val DISAMBIGUATION_TTL_MULTIPLIER: Long = 3L

    /** Venue queries before giving up on resolving an UNKNOWN send outcome. */
    const val UNKNOWN_RESOLVE_ATTEMPTS: Int = 4
}
