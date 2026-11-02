package com.qkt.observe.insights

import com.qkt.events.CostIncurred
import com.qkt.events.FundingShare

/**
 * A strategy's [share] of a venue's perpetual funding, booked as [cost], as a `broker.deal` of entry `ADJUSTMENT`:
 * venue money beside the strategy's trades, moving no quantity, its `costs` naming the `FUNDING` paid (positive) or
 * credited (negative) in the record's currency. One deal per strategy share, ticketed `funding-<fundingId>-<strategy>`, so a
 * record sent again dedupes at the collector, e.g. `funding-tx-1-s1` for `s1`'s share of venue record `tx-1`.
 */
internal fun fundingDeal(
    cost: CostIncurred,
    share: FundingShare,
    venues: InsightsVenues,
): InsightsEnvelope {
    val broker = cost.symbol.substringBefore(':')
    val ticket = "funding-${share.fundingId}-${cost.strategyId}"
    return InsightsEnvelope(
        id = "deal-$broker-$ticket",
        seq = 0,
        ts = share.fundedAtMs,
        strategyId = cost.strategyId,
        type = "broker.deal",
        payload =
            mapOf(
                "broker" to broker,
                "dealTicket" to ticket,
                "positionTicket" to share.positionId,
                "symbol" to cost.symbol,
                "entry" to AdjustmentCycle.ENTRY,
                "legEntry" to AdjustmentCycle.ENTRY,
                "adjustment" to FUNDING,
                "qty" to 0,
                "price" to 0,
                "profit" to 0,
                "comment" to cost.reason,
                "ts" to share.fundedAtMs,
                "strategyId" to cost.strategyId,
                "positionId" to share.positionId,
                "attribution" to "position",
                "costs" to listOf(mapOf("kind" to FUNDING, "amount" to cost.amount, "currency" to share.currency)),
                "realizedSource" to "engine",
                "currency" to share.currency,
                "instrument" to venues.instrumentFields(cost.symbol),
            ).filterValues { it != null } + venues.fields(broker),
    )
}

private const val FUNDING = "FUNDING"
