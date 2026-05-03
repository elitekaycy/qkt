package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.SystemClock
import com.qkt.engine.Engine
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MockTickFeed
import com.qkt.strategy.EveryNthTickBuyStrategy

fun main() {
    val clock        = SystemClock()
    val ids          = SequentialIdGenerator()
    val priceTracker = MarketPriceTracker()
    val strategy     = EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0)
    val broker       = MockBroker(clock, priceTracker)
    val engine       = Engine(
        strategy, broker, clock, ids, priceTracker,
        onTrade = { t -> println("FILLED: ${t.side} ${t.quantity} ${t.symbol} @ ${t.price}") }
    )
    val feed = MockTickFeed("XAUUSD", startPrice = 2400.0, count = 100, clock = clock)

    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    println("Done.")
}
