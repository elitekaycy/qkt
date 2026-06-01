package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionProvider

/**
 * Constructs a [Broker] for a single [LiveSession]. Each session calls the factory
 * with its own bus/clock/priceTracker so per-session lifecycles stay clean.
 *
 * The DSL stream label (e.g. "EXNESS", "BYBIT_SPOT", "BYBIT_LINEAR") maps to one factory
 * in the daemon's registry. The factory hides the underlying protocol (MT5, REST, native
 * SDK, ...) — the venue label is the public identity, the protocol is an implementation detail.
 *
 * The [PositionProvider] is the session's engine-side view of open positions; brokers that
 * reconcile against venue positions (Bybit linear) compare against it, while balance- or
 * order-only brokers (MT5, Bybit spot, Paper) ignore it.
 *
 * The final `String?` is the owning strategy name when the session hosts a single
 * strategy (daemon path); null for multi-strategy or test paths. Stateful brokers (MT5)
 * use it to correlate venue-side orphan positions back to their strategy during startup
 * recovery; stateless brokers (Paper) ignore it.
 */
typealias BrokerFactory = (EventBus, Clock, MarketPriceTracker, PositionProvider, String?) -> Broker
