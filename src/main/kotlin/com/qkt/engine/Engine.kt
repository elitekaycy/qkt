package com.qkt.engine

import com.qkt.bus.EventBus
import com.qkt.events.TickEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.slf4j.LoggerFactory

/**
 * The single entry point that turns a raw [Tick] into a [TickEvent] on the bus.
 *
 * `Engine` is intentionally minimal — its only job is to update the [MarketPriceTracker]
 * so price-dependent reads see fresh data, then publish a [TickEvent] so strategies
 * and indicators can react. Everything else (signals, orders, fills, risk) is handled
 * by other components that subscribe to bus events.
 *
 * Callers feed ticks via [onTick]; the backtest replay engine and the live `TickFeed`
 * are the two production producers.
 */
class Engine(
    private val bus: EventBus,
    private val priceTracker: MarketPriceTracker,
) {
    private val log = LoggerFactory.getLogger(Engine::class.java)

    /**
     * Updates the price tracker and publishes a [TickEvent] for [tick].
     *
     * Must be called from a single thread — `Engine` is not thread-safe.
     */
    fun onTick(tick: Tick) {
        priceTracker.update(tick.symbol, tick.price)
        log.debug("ingested tick {} @ {}", tick.symbol, tick.price)
        bus.publish(TickEvent(tick))
    }
}
