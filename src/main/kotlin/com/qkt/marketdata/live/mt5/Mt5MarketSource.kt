package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.MT5Symbol
import com.qkt.broker.mt5.SymbolCalendars
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.time.Instant
import kotlin.math.abs
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
 * [symbolCalendars] gates the live-ticks poller per asset class: the poller idles only when every
 * configured calendar is out of session. Defaults to the profile's resolver (all-FX unless the
 * profile declares a `calendars` block), so FX/metals behave as before and a crypto-bearing
 * profile keeps ticking 24/7.
 */
class Mt5MarketSource(
    private val profile: MT5BrokerProfile,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock(),
    private val symbolCalendars: SymbolCalendars = profile.symbolCalendars,
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
                    pollIntervalMs = profile.tickPollIntervalMs,
                    http = http,
                    serverTimeZone = profile.serverTimeZone,
                    clock = clock,
                    symbolCalendars = symbolCalendars,
                    apiKey = profile.apiKey,
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
        val bareSymbol = symbol.removePrefix(prefix)
        val wire = symbolMap.toBroker(bareSymbol)
        val candles =
            Mt5BarFetcher(
                profile.gatewayUrl,
                http,
                profile.serverTimeZone,
                normalizeBidBarsToMid = true,
                apiKey = profile.apiKey,
            ).fetchRange(wire, window, range)
                .map { candle -> candle.copy(symbol = symbol) }
                .toList()
        validateRecentTimeBase(bareSymbol, wire, window, range, candles)
        return candles.asSequence()
    }

    private fun validateRecentTimeBase(
        bareSymbol: String,
        wireSymbol: String,
        window: TimeWindow,
        range: TimeRange,
        candles: List<Candle>,
    ) {
        val nowMs = clock.now()
        if (abs(range.to.toEpochMilli() - nowMs) > RECENT_RANGE_TOLERANCE_MS) return
        if (!symbolCalendars.calendarFor(bareSymbol).isInSession(bareSymbol, Instant.ofEpochMilli(nowMs))) return
        require(candles.isNotEmpty()) {
            "MT5 time-base mismatch for $bareSymbol: no decoded bar remained in the recent UTC range; " +
                "set gateway MT5_SERVER_UTC_OFFSET_SECONDS=0 and verify " +
                "server_time_zone=${profile.serverTimeZone.id}"
        }
        val tick =
            Mt5TickClient(
                profile.gatewayUrl,
                http,
                profile.serverTimeZone,
                profile.apiKey,
            ).fetchOnce(
                wireSymbol,
                nowMs,
            )
        val newestClosedBarEndMs = candles.maxOf { it.endTime }
        val barAgeMs = tick.brokerTimeMs - newestClosedBarEndMs
        val maxAgeMs = maxOf(window.durationMs * 3L, MIN_RECENT_BAR_AGE_MS)
        require(barAgeMs in 0L..maxAgeMs) {
            "MT5 time-base mismatch for $bareSymbol: " +
                "newest closed bar end=${Instant.ofEpochMilli(newestClosedBarEndMs)}, " +
                "tick=${Instant.ofEpochMilli(tick.brokerTimeMs)}, deltaMs=$barAgeMs; " +
                "set gateway MT5_SERVER_UTC_OFFSET_SECONDS=0 and verify server_time_zone=${profile.serverTimeZone.id}"
        }
    }

    override fun close() {}

    companion object {
        private const val RECENT_RANGE_TOLERANCE_MS: Long = 5 * 60_000L
        private const val MIN_RECENT_BAR_AGE_MS: Long = 5 * 60_000L
    }
}
