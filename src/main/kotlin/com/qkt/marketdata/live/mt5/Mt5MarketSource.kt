package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.MT5Symbol
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import okhttp3.OkHttpClient

/**
 * [MarketSource] backed by an `mt5-gateway` HTTP endpoint. Reuses the [profile]
 * already configured for the [com.qkt.broker.mt5.MT5Broker] so the data and execution
 * sides agree on symbol translation (suffix + aliases).
 *
 * Routes symbols prefixed `<NAME>:` where `<NAME>` is `profile.name.uppercase()`. The
 * prefix is stripped before applying [profile]'s symbol policy, so `EXNESS:XAUUSD`
 * resolves on the wire to `XAUUSDm` for an Exness profile with `suffix = "m"`.
 *
 * [calendar] gates the live-ticks poller: when supplied and the calendar reports
 * out-of-session for the wire symbols, the poller idles instead of hitting the gateway.
 * Defaults to [TradingCalendar.fxDefault] for FX/metals semantics.
 */
class Mt5MarketSource(
    private val profile: MT5BrokerProfile,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock(),
    private val calendar: TradingCalendar? = TradingCalendar.fxDefault(),
) : MarketSource,
    AutoCloseable {
    override val name: String = "MT5:${profile.name}"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

    private val prefix: String = "${profile.name.uppercase()}:"
    private val symbolMap = MT5Symbol(profile.symbolPolicy)

    override fun supports(symbol: String): Boolean = symbol.startsWith(prefix)

    override fun liveTicks(symbols: List<String>): TickFeed {
        require(symbols.all { supports(it) }) { "$name cannot serve $symbols" }
        val wireToQkt: Map<String, String> =
            symbols.associateBy { qkt -> symbolMap.toBroker(qkt.removePrefix(prefix)) }
        return LiveTickFeed(
            source =
                Mt5TickFeedSource(
                    baseUrl = profile.gatewayUrl,
                    symbolMap = wireToQkt,
                    pollIntervalMs = profile.pollIntervalMs,
                    http = http,
                    clock = clock,
                    calendar = calendar,
                ),
            queueCapacity = 10_000,
        )
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        require(supports(symbol)) { "$name cannot serve $symbol" }
        val wire = symbolMap.toBroker(symbol.removePrefix(prefix))
        return Mt5BarFetcher(profile.gatewayUrl, http).fetchRange(wire, window, range)
    }

    override fun close() {}
}
