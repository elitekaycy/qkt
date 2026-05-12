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
}
