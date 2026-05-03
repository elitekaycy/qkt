package com.qkt.broker

import com.qkt.common.Clock
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceProvider

class MockBroker(
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider
) : Broker {

    override fun execute(order: Order): Trade? {
        require(order.quantity > 0.0) { "Order quantity must be > 0: $order" }
        val fillPrice = when (order.type) {
            OrderType.MARKET -> priceProvider.lastPrice(order.symbol) ?: return null
            OrderType.LIMIT, OrderType.STOP ->
                order.price ?: error("LIMIT/STOP requires price: $order")
        }
        return Trade(
            orderId = order.id,
            symbol = order.symbol,
            price = fillPrice,
            quantity = order.quantity,
            side = order.side,
            timestamp = clock.now()
        )
    }
}
