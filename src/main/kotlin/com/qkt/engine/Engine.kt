package com.qkt.engine

import com.qkt.bus.EventBus
import com.qkt.events.TickEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.slf4j.LoggerFactory

class Engine(
    private val bus: EventBus,
    private val priceTracker: MarketPriceTracker,
) {
    private val log = LoggerFactory.getLogger(Engine::class.java)

    fun onTick(tick: Tick) {
        priceTracker.update(tick.symbol, tick.price)
        log.debug("ingested tick {} @ {}", tick.symbol, tick.price)
        bus.publish(TickEvent(tick))
    }
}
