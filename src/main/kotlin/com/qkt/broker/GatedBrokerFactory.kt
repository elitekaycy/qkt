package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionProvider

/**
 * A [BrokerFactory] that refuses while [refusal] names a reason, e.g. an account the daemon could not reach at boot
 * and so never verified (#1642). A session checks [ensureOpen] on every factory it needs before it builds any of
 * them, so a strategy trading two accounts, one of them refused, builds nothing and fails to deploy.
 */
class GatedBrokerFactory(
    private val inner: BrokerFactory,
    private val refusal: () -> String?,
) : BrokerFactory {
    /** Throws [IllegalStateException] with the refusal while there is one. */
    fun ensureOpen() {
        refusal()?.let { error(it) }
    }

    override fun invoke(
        bus: EventBus,
        clock: Clock,
        prices: MarketPriceTracker,
        positions: PositionProvider,
        strategy: String?,
    ): Broker {
        ensureOpen()
        return inner(bus, clock, prices, positions, strategy)
    }
}
