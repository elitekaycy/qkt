package com.qkt.broker

import com.qkt.common.Side
import java.math.BigDecimal

/** How a venue accounts for simultaneous long and short exposure on one symbol. */
enum class PositionAccountingMode {
    /** Opposite fills collapse into one signed position. */
    NETTING,

    /** Long and short tickets coexist and must be reconciled independently. */
    HEDGING,

    /** Venue mode could not be confirmed; reconciliation must conservatively preserve direction. */
    UNKNOWN,
}

/** Venue account snapshot for observability — what the broker says the whole account is worth. */
data class BrokerAccountState(
    /** Venue identifier in uppercase, e.g. "EXNESS". */
    val broker: String,
    /** Account currency code, e.g. "USD". */
    val currency: String,
    /** Closed-trade balance in account currency. */
    val balance: BigDecimal,
    /** Balance plus floating profit of open positions. */
    val equity: BigDecimal,
    /** Margin currently in use; null when the venue omits it. */
    val margin: BigDecimal?,
    /** Margin still available for new positions; null when omitted. */
    val marginFree: BigDecimal?,
    /** Floating profit of all open positions; null when omitted. */
    val openProfit: BigDecimal?,
    /** Equity / used margin x 100 (a percent); null when no margin is in use. */
    val marginLevel: BigDecimal?,
    /** The venue's account login — an MT5 login number "436804390", a VGP `account_login` "paper". */
    val login: String,
    /** Broker trade server, e.g. "Exness-MT5Trial9"; empty when the venue has none. */
    val server: String,
    /** Account holder name as the venue records it, e.g. "qkt-hedge-straddle". */
    val name: String,
    /** The connector, venue and account id this state came from; null for brokers that state none. */
    val venue: VenueIdentity? = null,
)

/** One executed venue deal (an in/out leg in MT5 terms), used for insights history. */
data class BrokerDeal(
    /** Venue identifier in uppercase, e.g. "EXNESS". */
    val broker: String,
    /** Venue ticket of this deal. */
    val dealTicket: String,
    /** Ticket of the position this deal opened, reduced, or closed; null when unknown. */
    val positionTicket: String?,
    /** Ticket of the order that produced this deal; null when unknown. */
    val orderTicket: String?,
    /** qkt-side symbol with broker prefix, e.g. "EXNESS:XAUUSD". */
    val symbol: String,
    val side: Side,
    /** Position lifecycle leg: IN | OUT | INOUT | OUT_BY. */
    val entry: String,
    val qty: BigDecimal,
    val price: BigDecimal,
    /** Realized profit booked by this deal, in account currency. */
    val profit: BigDecimal,
    val commission: BigDecimal,
    val swap: BigDecimal,
    /** Venue magic number the deal was booked under; null when the venue has none. */
    val magic: Int?,
    val comment: String?,
    /** Deal execution time, UTC epoch millis. */
    val ts: Long,
    /** Broker fee distinct from commission and swap; null when the venue omits it. */
    val fee: BigDecimal? = null,
    /** Full untruncated client order id the deal was placed under; null when unknown. */
    val clientOrderId: String? = null,
    /** Why a closing deal closed; null for an opening deal or when unknown. */
    val closeReason: CloseReason? = null,
    /** Typed costs the venue charged on this deal (empty when it states them as the scalars above). */
    val costs: List<com.qkt.accounting.VenueCost> = emptyList(),
    /** The strategy the connector knows placed this deal, exactly (not by comment); null when unknown. */
    val strategyId: String? = null,
    /** Who computed [profit]: "venue" (the venue reported it) or "engine" (qkt's fill accounting). */
    val realizedSource: String? = null,
    /** The position a reversing (INOUT) deal opened, when it is not [positionTicket]. */
    val nextPositionTicket: String? = null,
)

/** One venue position ticket, broker-valued. */
data class BrokerPositionTicket(
    /** Venue ticket of the open position. */
    val ticket: String,
    /** qkt-side symbol with broker prefix, e.g. "EXNESS:XAUUSD". */
    val symbol: String,
    val side: Side,
    val qty: BigDecimal,
    val entryPrice: BigDecimal,
    /** Current market price as the venue reports it; null when not exposed. */
    val currentPrice: BigDecimal?,
    /** Floating profit in account currency; null when not exposed. */
    val profit: BigDecimal?,
    /** Accumulated swap in account currency; null when not exposed. */
    val swap: BigDecimal?,
    /** Open time, UTC epoch millis; null when not exposed. */
    val openedAt: Long?,
    val comment: String?,
    /** Stop-loss currently attached at the venue; null when unsupported. Zero means absent on MT5. */
    val stopLoss: BigDecimal? = null,
    /** Take-profit currently attached at the venue; null when unsupported. Zero means absent on MT5. */
    val takeProfit: BigDecimal? = null,
    /** Stop-loss qkt last requested for this ticket; null when none was requested or attribution is unknown. */
    val requestedStopLoss: BigDecimal? = null,
    /** Take-profit qkt last requested for this ticket; null when none was requested or attribution is unknown. */
    val requestedTakeProfit: BigDecimal? = null,
    /** Venue magic number the position was opened under; null when the venue has none. */
    val magic: Int? = null,
    /** Full untruncated client order id that opened the position; null when unknown. */
    val clientOrderId: String? = null,
    /** The strategy the connector knows holds this position, exactly; null when unknown or shared. */
    val strategyId: String? = null,
)

/** One resting (pending) order at the venue — a limit/stop waiting to trigger. */
data class BrokerPendingOrder(
    /** Venue ticket of the pending order. */
    val ticket: String,
    /** qkt-side symbol with broker prefix, e.g. "EXNESS:XAUUSD". */
    val symbol: String,
    val side: Side,
    /** Venue order type string, e.g. "ORDER_TYPE_BUY_LIMIT". */
    val orderType: String,
    val qty: BigDecimal,
    /** Trigger/limit price the order rests at; null when the venue omits it. */
    val price: BigDecimal?,
    /** Stop-loss attached to the resting order; null when unsupported. Zero means absent on MT5. */
    val stopLoss: BigDecimal? = null,
    /** Take-profit attached to the resting order; null when unsupported. Zero means absent on MT5. */
    val takeProfit: BigDecimal? = null,
    /** Expiry, UTC epoch millis; null when good-till-cancelled or not exposed. */
    val expiresAt: Long? = null,
    /** Placement time, UTC epoch millis; null when not exposed. */
    val createdAt: Long? = null,
    /** Venue magic number; null when the venue has none. */
    val magic: Int? = null,
    val comment: String? = null,
    /** Full untruncated client order id; null when unknown. */
    val clientOrderId: String? = null,
    /** The strategy the connector knows placed this order, exactly; null when unknown. */
    val strategyId: String? = null,
)
