package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.marketdata.MarketPriceTracker

/**
 * Constructs a [Broker] for a single [LiveSession]. Each session calls the factory
 * with its own bus/clock/priceTracker so per-session lifecycles stay clean.
 *
 * The DSL stream label (e.g. "EXNESS", "BYBIT") maps to one factory in the daemon's
 * registry. The factory hides the underlying protocol (MT5, REST, native SDK, ...)
 * — the venue label is the public identity, the protocol is an implementation detail.
 */
typealias BrokerFactory = (EventBus, Clock, MarketPriceTracker) -> Broker
