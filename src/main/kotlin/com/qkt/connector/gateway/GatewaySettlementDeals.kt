package com.qkt.connector.gateway

import com.qkt.broker.BrokerDeal
import com.qkt.broker.CloseReason
import java.math.BigDecimal

/**
 * Settlement [s] of contract [symbol] as the venue deal that closed one strategy's holding ([close]): at the
 * settlement price, with the leg, position and realized P&L qkt booked for it and its share of the venue's
 * costs, so a contract held to expiry ends its position in the account's deals as a fill would.
 */
internal fun settlementDeal(
    broker: String,
    symbol: String,
    s: WireSettlement,
    close: GatewaySettledClose,
): BrokerDeal {
    val id = "$SETTLE_PREFIX${s.symbol}:${close.fact.strategyId}"
    return BrokerDeal(
        broker = broker,
        dealTicket = id,
        positionTicket = close.fact.leg.positionId,
        orderTicket = null,
        symbol = symbol,
        side = close.side,
        entry = close.fact.leg.entry,
        qty = close.quantity,
        price = BigDecimal(s.price),
        profit = close.fact.grossRealized,
        commission = BigDecimal.ZERO,
        swap = BigDecimal.ZERO,
        magic = null,
        comment = id,
        ts = s.time,
        fee = close.venueCosts.takeIf { it.signum() != 0 }?.negate(),
        clientOrderId = id,
        closeReason = CloseReason.EXPIRY,
        strategyId = close.fact.strategyId,
        realizedSource = "engine",
        nextPositionTicket = close.fact.leg.nextPositionId,
    )
}
