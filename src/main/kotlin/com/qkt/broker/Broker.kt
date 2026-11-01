package com.qkt.broker

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * A strategy's order-entry session on one account, from [com.qkt.connectivity.TradingAccount.orderEntry].
 *
 * Sessions receive [OrderRequest]s and publish results back through
 * [com.qkt.events.BrokerEvent]s on the bus. The interface is intentionally narrow —
 * submit, cancel, and (optionally) modify — because the engine handles everything
 * around it: order management, P&L attribution, position tracking, risk.
 *
 * Implementations: [PaperBroker] (in-process simulator), [com.qkt.connector.mt5.MT5Broker]
 * (MetaTrader 5 via gateway), [com.qkt.connector.gateway.GatewayBroker]
 * (any VGP venue gateway: Bybit, Deribit, …), [CompositeBroker] (multi-venue router).
 */
interface Broker : VenueReads {
    /** Human-readable broker identifier — appears in logs and status output. */
    val name: String

    /** Order types this broker accepts directly without engine-side decomposition. */
    val capabilities: Set<OrderTypeCapability>

    /**
     * Returns the capabilities for a specific [symbol].
     *
     * Defaults to the broker-wide [capabilities]; override when a venue supports
     * different shapes for different instruments.
     */
    fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> = capabilities

    /**
     * Returns `true` if this broker can route orders for [symbol].
     *
     * Used by [com.qkt.broker.composite.CompositeBroker] to pick the right leaf.
     */
    fun supports(symbol: String): Boolean = true

    /**
     * Submits [request] to the venue.
     *
     * Returns [SubmitAck] immediately; fill or rejection arrives asynchronously as a
     * [com.qkt.events.BrokerEvent] on the bus.
     */
    fun submit(request: OrderRequest): SubmitAck

    /** Cancels the working order with client-assigned [orderId]. No-op if already terminal. */
    fun cancel(orderId: String)

    /**
     * Modifies an existing working order.
     *
     * Optional capability — brokers that don't support modification throw
     * [UnsupportedOperationException].
     */
    fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck = throw UnsupportedOperationException("$name does not support modify")

    /**
     * Move an open position's stop-loss / take-profit on the venue (not a pending order). Used to
     * keep a trailing stop's level mirrored onto the position so the broker still closes it if the
     * engine is offline. Default no-op (accepted) for brokers without venue-attached position SL/TP.
     */
    fun modifyPosition(
        ticket: String,
        sl: BigDecimal? = null,
        tp: BigDecimal? = null,
    ): SubmitAck = SubmitAck(ticket, ticket, accepted = true)

    /**
     * Asynchronous variant of [modifyPosition]. Connectors with blocking venue I/O override this
     * so fill handlers never wait on a network round-trip. The default preserves immediate,
     * in-process brokers while reporting completion through [onResult].
     */
    fun modifyPositionAsync(
        ticket: String,
        sl: BigDecimal? = null,
        tp: BigDecimal? = null,
        onResult: (SubmitAck) -> Unit,
    ) {
        onResult(modifyPosition(ticket, sl, tp))
    }

    /**
     * True when [symbol]'s venue position is the net of several strategies sharing one netting account:
     * startup reconcile then trusts each strategy's persisted book, and the broker checks the total.
     */
    fun isAccountWide(symbol: String): Boolean = false

    /**
     * Re-establish venue-side tracking for OCO legs recovered from the persistor on
     * restart. Brokers join each [com.qkt.execution.ManagedOrder] to live venue state by
     * ticket and, for a leg that filled while the daemon was down, republish its
     * [com.qkt.events.BrokerEvent.OrderFilled]. Implementations must throw when venue truth
     * cannot be established, so startup refuses to trade on assumed state.
     *
     * Returns the client order ids the venue could account for — matched to a working order,
     * an open position, or a ticket the broker now tracks to its terminal state. An id missing
     * from the result has no venue counterpart at all; the caller retires it rather than
     * carrying a phantom working order (and its exposure) for the rest of the session (#1048
     * legacy state). Brokers without venue truth vouch for every order — the default.
     *
     * [bookedTickets] are the venue position tickets the position ledger already holds for
     * these strategies. An execution on such a ticket was booked before the restart: the broker
     * must still join the order to its ticket but must not republish that execution (#1096).
     */
    fun recoverPendingOrders(
        orders: List<com.qkt.execution.ManagedOrder>,
        bookedTickets: Set<String> = emptySet(),
    ): Set<String> = orders.mapTo(LinkedHashSet()) { it.id }

    /**
     * Hand the broker a live view of the ledger legs that carry one of its venue tickets.
     * A broker with venue truth compares them against its position snapshots: a ticket the
     * ledger holds but the venue no longer has closed while nobody was watching, and the
     * broker must publish that close so the ledger retires the leg (#1097). Read from the
     * broker's own polling thread — the supplier must be safe to call off the engine thread.
     * Brokers without venue truth ignore it — the default.
     */
    fun watchBookedLegs(supplier: () -> List<BookedLeg>) {}

    /**
     * An operator's resume: clears the stop a failed roll left on [strategyId] on each continuous stream where that is
     * safe, and says what became of every such stop. Brokers without continuous streams have none: the default.
     */
    fun releaseStreamStops(strategyId: String): List<StreamStopRelease> = emptyList()

    /**
     * Release venue-side resources held by this broker (poller threads, reconcilers,
     * HTTP connections, WebSocket transports). Called by the engine when the owning
     * [com.qkt.app.LiveSession] stops, so a long-running daemon that cycles strategies
     * doesn't accumulate idle pollers or executors. Default no-op — only brokers that
     * hold lifecycle resources override.
     *
     * Implementations must be idempotent: calling `shutdown` twice is harmless.
     */
    fun shutdown() {}

    /**
     * Whether any session this venue trades is open at [nowMs] per its calendar. Pollers whose
     * only job is to refresh venue state (equity, insights snapshots) back off while closed —
     * nothing changes over a weekend except the bill for asking. Defaults to always open.
     */
    fun marketOpen(nowMs: Long): Boolean = true

    /**
     * Whether [symbol] is inside a venue-scheduled intraday pause at [nowMs] (e.g. the 17:00
     * New York metals close). The market-data gate uses it to report an expected quote gap
     * as a pause rather than a feed fault; order suppression is unchanged. Defaults to never.
     */
    fun scheduledBreak(
        symbol: String,
        nowMs: Long,
    ): Boolean = false

    /**
     * Whether this venue takes an order on [symbol] at [nowMs]. Outside a symbol's trading session a venue refuses
     * every order, closing ones included (Exness quotes US stock CFDs from 06:00 New York and answers MT5 retcode
     * 10044 "session closed" to an opening order until 09:40), so qkt refuses them first ([com.qkt.risk.rules.VenueSession])
     * and holds engine-held triggers until it opens. A venue that knows each symbol's sessions
     * ([SymbolSessionProvider]) answers from them; any other takes orders at all times, as do the in-process venues
     * unless a replay gives them sessions.
     */
    fun acceptsOrders(
        symbol: String,
        nowMs: Long,
    ): Boolean = (this as? SymbolSessionProvider)?.symbolInSession(symbol, nowMs) ?: true

    /**
     * Phase 38: when true, this broker submits GTD orders with a venue-side expiration and
     * the venue self-cancels at the deadline; the engine's deadline-sweep in
     * [com.qkt.app.OrderManager] skips orders routed through this broker. When false
     * (default), the engine cancels the order at the deadline on the next tick.
     */
    val supportsNativeGtd: Boolean get() = false
}

/**
 * Opt-in capability for brokers that expose authoritative [Broker.marginLevel] data.
 *
 * Kept separate from [Broker] so adding the capability does not break existing broker
 * implementations compiled against an earlier qkt release.
 */
interface MarginLevelProvider {
    /** Whether margin-level reads are supported for this broker composition. */
    val supportsMarginLevel: Boolean
}
