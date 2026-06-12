package com.qkt.broker

import com.qkt.common.Side
import java.math.BigDecimal

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
)
