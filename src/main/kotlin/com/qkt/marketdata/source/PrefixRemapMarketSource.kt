package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed

/**
 * Exposes [delegate]'s symbols under a different `<NAME>:` prefix so several MT5 broker
 * profiles that share one market-data identity (same gateway, credentials, symbol policy,
 * poll cadence, calendars) can be served by a single upstream poller.
 *
 * The daemon configures one profile per strategy (`exness_s0..s24`) differing only in
 * `name`/`magic`; without this wrapper each profile would open its own tick poller against
 * the same gateway account. [com.qkt.cli.MarketSourceFactory] groups those profiles, points
 * the canonical profile's prefix straight at the shared source, and routes every other
 * profile's prefix through one of these wrappers.
 *
 * Translation is purely nominal: `LOCAL:XAUUSD` is served as `CANONICAL:XAUUSD` upstream and
 * every emitted [Tick]/[Candle] is stamped back with the requesting prefix, so strategies see
 * exactly the symbols they subscribed to. Live-feed lifecycle semantics
 * ([MarketDataLifecycleFeed]) are preserved when the delegate feed supports them:
 * `onDisconnect`/`onReconnect` handlers are forwarded with [MarketDataFeedScope.symbols]
 * rewritten to the local prefix, and `expectsContinuousDelivery`/`terminalFailureReason`
 * delegate unchanged — including end-of-feed (`null`) propagation from [TickFeed.next].
 */
class PrefixRemapMarketSource(
    private val delegate: MarketSource,
    /** Prefix the delegate serves, e.g. `EXNESS_S0:`. */
    private val delegatePrefix: String,
    /** Prefix this source exposes to subscribers, e.g. `EXNESS_S1:`. */
    private val localPrefix: String,
) : MarketSource {
    init {
        require(delegatePrefix.endsWith(":")) { "delegate prefix must end with ':': $delegatePrefix" }
        require(localPrefix.endsWith(":")) { "local prefix must end with ':': $localPrefix" }
    }

    override val name: String = delegate.name
    override val capabilities: Set<MarketSourceCapability> = delegate.capabilities

    override fun supports(symbol: String): Boolean =
        symbol.startsWith(localPrefix) && delegate.supports(toDelegate(symbol))

    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> =
        if (symbol.startsWith(localPrefix)) delegate.capabilitiesFor(toDelegate(symbol)) else emptySet()

    override fun liveTicks(symbols: List<String>): TickFeed {
        require(symbols.all { it.startsWith(localPrefix) }) { "$name cannot serve $symbols" }
        val feed = delegate.liveTicks(symbols.map(::toDelegate))
        val lifecycle = feed as? MarketDataLifecycleFeed
        return if (lifecycle != null) {
            RemapLifecycleFeed(feed, lifecycle, ::toLocal)
        } else {
            RemapTickFeed(feed, ::toLocal)
        }
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        require(symbol.startsWith(localPrefix)) { "$name cannot serve $symbol" }
        return delegate.bars(toDelegate(symbol), window, range).map { it.copy(symbol = symbol) }
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        require(symbol.startsWith(localPrefix)) { "$name cannot serve $symbol" }
        return delegate.ticks(toDelegate(symbol), range).map { it.copy(symbol = symbol) }
    }

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> {
        require(symbol.startsWith(localPrefix)) { "$name cannot serve $symbol" }
        return delegate.tickSlice(toDelegate(symbol), fromMs, toMs).map { it.copy(symbol = symbol) }
    }

    private fun toDelegate(symbol: String): String = delegatePrefix + symbol.removePrefix(localPrefix)

    private fun toLocal(symbol: String): String =
        if (symbol.startsWith(delegatePrefix)) localPrefix + symbol.removePrefix(delegatePrefix) else symbol
}

/** Rewrites emitted tick symbols through [toLocal]; `null` (end-of-feed) passes through. */
private open class RemapTickFeed(
    private val delegate: TickFeed,
    private val toLocal: (String) -> String,
) : TickFeed {
    override fun next(): Tick? = delegate.next()?.let { it.copy(symbol = toLocal(it.symbol)) }

    override fun close() = delegate.close()
}

/**
 * [RemapTickFeed] that also surfaces the delegate's [MarketDataLifecycleFeed] contract:
 * handlers register on the delegate and see scopes with symbols rewritten through [toLocal];
 * `expectsContinuousDelivery` and `terminalFailureReason` are inherited unchanged.
 */
private class RemapLifecycleFeed(
    delegate: TickFeed,
    private val lifecycle: MarketDataLifecycleFeed,
    private val toLocal: (String) -> String,
) : RemapTickFeed(delegate, toLocal),
    MarketDataLifecycleFeed {
    override val expectsContinuousDelivery: Boolean
        get() = lifecycle.expectsContinuousDelivery

    override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
        lifecycle.onDisconnect { scope -> handler(scope.copy(symbols = scope.symbols?.map(toLocal))) }
    }

    override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
        lifecycle.onReconnect { scope -> handler(scope.copy(symbols = scope.symbols?.map(toLocal))) }
    }

    override fun terminalFailureReason(): String? = lifecycle.terminalFailureReason()
}
