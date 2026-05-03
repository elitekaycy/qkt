package com.qkt.engine

import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy

class Engine(
    private val strategy: Strategy,
    private val broker: Broker,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val priceTracker: MarketPriceTracker,
    private val onTrade: (Trade) -> Unit = {}
) {

    fun onTick(tick: Tick) {
        priceTracker.update(tick.symbol, tick.price)
        strategy.onTick(tick) { signal -> route(signal) }
    }

    private fun route(signal: Signal) {
        val order = signal.toOrder(idGenerator.next(), clock.now())
        val trade = broker.execute(order) ?: return
        onTrade(trade)
    }

    private fun Signal.toOrder(id: String, ts: Long): Order = when (this) {
        is Signal.Buy  -> Order(id, symbol, Side.BUY,  size, OrderType.MARKET, null, ts)
        is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
    }
}
