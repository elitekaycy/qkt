package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.pnl.BookBalanceView

/**
 * Phase 27: refuse to deploy a strategy whose `STACK_AT` symbols route to a broker
 * that doesn't declare [OrderTypeCapability.MULTI_POSITION_PER_SYMBOL].
 * The capability is checked per-symbol via [Broker.capabilitiesFor]
 * so [com.qkt.broker.CompositeBroker] routing differences across symbols are honored.
 */
internal fun requireMultiPositionCapability(
    strategyId: String,
    strategy: DslCompiledStrategy,
    broker: Broker,
) {
    for (symbol in strategy.multiPositionPerSymbolSymbols) {
        val caps = broker.capabilitiesFor(symbol)
        require(OrderTypeCapability.MULTI_POSITION_PER_SYMBOL in caps) {
            "Strategy '$strategyId' uses STACK_AT on $symbol but routing broker " +
                "'${broker.name}' does not declare MULTI_POSITION_PER_SYMBOL"
        }
    }
}

/**
 * #301: refuse to deploy a strategy that binds a volume-weighted indicator (VWAP/OBV) to a feed
 * that can't supply volume — otherwise the indicator never becomes ready and the strategy
 * silently never fires. Only enforced when the source actually serves the symbol's stream
 * (`TICKS`/`BARS`/`LIVE_TICKS`); a source that doesn't (e.g. [com.qkt.marketdata.source.NullMarketSource]
 * behind an injected-tick backtest) carries no judgment about volume. Per-symbol via
 * [MarketSource.capabilitiesFor] so routing across a basket is honored.
 */
internal fun requireVolumeCapability(
    strategyId: String,
    strategy: DslCompiledStrategy,
    source: MarketSource,
) {
    for (symbol in strategy.volumeRequiringSymbols) {
        val caps = source.capabilitiesFor(symbol)
        val servesStream =
            caps.any {
                it == MarketSourceCapability.TICKS ||
                    it == MarketSourceCapability.BARS ||
                    it == MarketSourceCapability.LIVE_TICKS
            }
        require(!servesStream || MarketSourceCapability.VOLUME in caps) {
            "Strategy '$strategyId' binds a volume-weighted indicator (VWAP/OBV) on $symbol but its " +
                "data feed ('${source.name}') does not supply volume — bind a volume-bearing feed or remove the indicator"
        }
    }
}

/**
 * Refuse to deploy a strategy that sizes with `RISK OF BOOK` when no portfolio book is bound, so
 * the missing capability fails at deploy rather than at the first signal.
 */
internal fun requireBookCapability(
    strategyId: String,
    strategy: DslCompiledStrategy,
    bookBalance: BookBalanceView?,
) {
    require(!strategy.usesBookSizing || bookBalance != null) {
        "Strategy '$strategyId' sizes with RISK OF BOOK but no portfolio book is bound — " +
            "deploy it as a child of a PORTFOLIO with CAPITAL, or use RISK/PCT RISK sizing"
    }
}
