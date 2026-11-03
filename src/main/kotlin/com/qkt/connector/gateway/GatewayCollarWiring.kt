package com.qkt.connector.gateway

import com.qkt.broker.ORDER_COLLAR_FRAC
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

/** One market order being worked by [GatewayMarketCollar]. Touched by one venue answer at a time, and the engine's cancel. */
internal class CollaredMarketOrder(
    val request: OrderRequest.Market,
) {
    @Volatile var attempts = 1

    @Volatile var filled: BigDecimal = BigDecimal.ZERO

    @Volatile var sentAt = 0L

    @Volatile var waiting = false

    @Volatile var cancelling = false

    @Volatile var last = "no attempt answered"
}

/** Whether a market order on a listed [kind] goes collared: see the futures docs for the evidence behind the list. */
internal fun collarsMarketOrders(kind: String?): Boolean = kind == "future"

/**
 * This session's collar for a broker holding [positions], at [ORDER_COLLAR_FRAC]. Null [prices]
 * collar nothing. An exit closes a ticket or a leg, or strictly reduces the strategy's position and the account's.
 */
internal fun GatewaySession.marketCollar(
    prices: MarketPriceProvider?,
    clock: Clock,
    positions: PositionProvider,
    send: (OrderRequest) -> Unit,
    publish: (BrokerEvent) -> Unit,
) = GatewayMarketCollar(
    ORDER_COLLAR_FRAC,
    prices ?: MarketPriceTracker(),
    clock,
    collars = { prices != null && collarsMarketOrders(symbols.instrument(it)?.kind) },
    exits = { request ->
        request.closesTicket != null ||
            request.legIntent is LegIntent.Close ||
            GatewayOrders.reducesBoth(
                request,
                positions,
                symbols.venue(request.symbol)?.let(account::quantity) ?: BigDecimal.ZERO,
            )
    },
    send = send,
    later = placement::after,
    publish = publish,
)
