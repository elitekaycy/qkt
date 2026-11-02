package com.qkt.broker

import java.math.BigDecimal

/**
 * A booking a venue made on the account that is not a trade: a stock CFD's dividend adjustment, a charge, a
 * correction, interest, tax, or a deposit or withdrawal. [amount] is the money it moved, in account currency (a long
 * stock CFD's dividend is positive, a short's negative). [symbol] and [positionTicket] are set when the venue ties it
 * to an instrument or a position, which is what makes it a strategy's cost rather than the account's.
 */
data class BrokerAdjustment(
    /** Venue identifier in uppercase, e.g. "EXNESS". */
    val broker: String,
    val dealTicket: String,
    val kind: AdjustmentKind,
    val amount: BigDecimal,
    /** qkt-side symbol with broker prefix, e.g. "EXNESS:AAPL"; null for an account-wide booking. */
    val symbol: String?,
    val positionTicket: String?,
    val magic: Int?,
    val comment: String?,
    /** Booking time, UTC epoch millis. */
    val ts: Long,
) {
    /** True when the booking belongs to the account, not to any position: a deposit, withdrawal, credit or bonus. */
    val accountLevel: Boolean get() = symbol == null && positionTicket == null
}

/** What a [BrokerAdjustment] is. */
enum class AdjustmentKind {
    DIVIDEND,
    CHARGE,
    CORRECTION,
    COMMISSION,
    INTEREST,
    TAX,
    BONUS,
    CREDIT,
    BALANCE,
    UNKNOWN,
}

/** A broker that can list the [BrokerAdjustment]s booked in `[from, to]` (UTC epoch ms); a failed read throws. */
interface AdjustmentSource {
    fun adjustments(
        from: Long,
        to: Long,
    ): List<BrokerAdjustment>
}
