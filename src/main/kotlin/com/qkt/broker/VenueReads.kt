package com.qkt.broker

/**
 * What a broker can say about its venue account without changing anything: its positions, tickets, resting orders,
 * deals, equity and margin. Every member has a default that means "this venue reports nothing", so an adapter
 * implements only the reads its venue answers, and every caller (reconciliation, Insights, risk floors) reads them the
 * same way whichever connector is behind them. A failed read throws or returns null as each member states; it never
 * reads as an empty account.
 */
interface VenueReads {
    /**
     * Snapshot of currently-open positions on the venue, keyed by qkt-side symbol: a list per symbol,
     * since hedge-mode brokers (MT5) hold a long and a short as two tickets the reconciler
     * matches separately; one-way brokers and PaperBroker return at most one entry. Used at deploy time
     * by [com.qkt.persistence.LegBookReconciler]; empty by default (no venue-side state).
     */
    fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> = emptyMap()

    /** Whether [accountEquity] is a supported venue operation rather than the default null view. */
    val supportsAccountEquity: Boolean get() = false

    /**
     * Venue margin level as a percent (equity / used margin x 100), or null when the
     * venue doesn't report one (paper brokers, spot venues, no margin in use). Powers
     * the pre-entry margin floor — MT5 force-closes positions around 50%, so entries
     * should stop long before that.
     */
    fun marginLevel(): java.math.BigDecimal? = null

    /** Venue-reported account equity, or null when the venue doesn't expose one. */
    fun accountEquity(): java.math.BigDecimal? = null

    /**
     * Full venue account snapshot (balance, equity, margin) for observability. Null when
     * the venue has none to report (paper brokers) or the read failed.
     */
    fun accountState(): BrokerAccountState? = null

    /**
     * Deals (executed in/out legs) the venue booked in `[from, to]` (UTC epoch ms),
     * e.g. one position closed by TP yields an IN deal and an OUT deal carrying the
     * realized profit. Empty when the venue keeps none; a failed read throws (never "no deals").
     */
    fun deals(
        from: Long,
        to: Long,
    ): List<BrokerDeal> = emptyList()

    /**
     * Per-ticket open positions as the venue values them — unlike [getOpenPositions],
     * which aggregates per symbol for reconciliation, this keeps each ticket with its
     * broker-reported profit and swap. Empty when unsupported or the read failed.
     */
    fun positionTickets(): List<BrokerPositionTicket> = emptyList()

    /** Whether [positionTickets] is authoritative venue truth rather than the empty default. */
    val supportsPositionTickets: Boolean get() = false

    /**
     * Resting (pending) orders at the venue - limits/stops waiting to trigger, with
     * their protective levels and expiry. Empty when unsupported or the read failed.
     */
    fun pendingOrders(): List<BrokerPendingOrder> = emptyList()

    /** Position accounting mode for [symbol]; unknown modes must be reconciled as hedging. */
    fun positionAccountingMode(symbol: String): PositionAccountingMode = PositionAccountingMode.UNKNOWN
}
