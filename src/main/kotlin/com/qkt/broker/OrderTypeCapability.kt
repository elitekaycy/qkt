package com.qkt.broker

/**
 * Order shapes a broker can route directly without engine-side decomposition.
 *
 * The engine consults a broker's [Broker.capabilities] when deciding whether to send
 * a composite (Bracket, OCO, OTO, ...) as a single request or to split it into atomic
 * legs the broker can handle.
 */
enum class OrderTypeCapability {
    /** Simple market order. */
    MARKET,

    /** Resting limit order. */
    LIMIT,

    /** Stop order — fires a market when stopPrice prints. */
    STOP,

    /** Stop-limit — fires a limit when stopPrice prints. */
    STOP_LIMIT,

    /** Entry with attached take-profit + stop-loss children, atomic at the venue. */
    BRACKET,

    /** If-touched — fires a market or limit when triggerPrice prints (either direction). */
    IF_TOUCHED,

    /** Broker accepts [OrderModification] on a working order. */
    MODIFY,

    /** Two pending orders linked one-cancels-other; whichever fills, the other auto-cancels. */
    OCO,

    /** Server-side trailing stop that follows the favorable price by a fixed distance. */
    TRAILING_STOP,

    /**
     * The broker supports holding multiple positions on the same symbol simultaneously.
     *
     * Phase 27: required for strategies that use `STACK_AT` clauses. MT5 supports this
     * natively (each ticket is independent). Bybit Linear supports it in hedge mode only.
     * Bybit Spot is netting-only and does not.
     */
    MULTI_POSITION_PER_SYMBOL,

    /**
     * The broker can set or adjust the attached SL/TP on an already-open position
     * (MT5 `TRADE_ACTION_SLTP`, wrapped by `modifyPosition`). The venue then closes
     * that position itself when a level is hit — on a hedging account this closes the
     * exact ticket rather than opening a counter, which a standalone resting exit order
     * would do. Lets bracket exits attach to the position instead of resting separately.
     */
    POSITION_MODIFY,
}
