package com.qkt.events

import java.math.BigDecimal

/**
 * A venue charged ([amount] positive) or credited ([amount] negative) the account [amount] of [currency]
 * for holding perpetual [symbol] at [fundedAtMs], charged on [basis], the account's signed quantity the
 * venue charged on. It is not about any order: each strategy holding [symbol] books its own part,
 * `amount × holding / basis`, so strategies long and short on one account, or a position another tool
 * holds on it, each keep their own funding. [fundingId] is the venue's record id: a record heard twice is
 * booked once. A record the venue named no position for is [positionless]: a venue that realizes funding on a
 * trade stamps it at that trade, so [basis] is what qkt's sessions held together just before [fundedAtMs], and
 * each strategy books by what it held then, never by what it holds when the record is heard.
 */
data class FundingCharged(
    val fundingId: String,
    val symbol: String,
    val amount: BigDecimal,
    val currency: String,
    val basis: BigDecimal,
    val fundedAtMs: Long,
    val positionless: Boolean = false,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : BrokerEvent

/** How far back a gateway session replays the account's funding when it starts, to book what it missed. */
const val FUNDING_REPLAY_MS = 7 * 86_400_000L
