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
 *
 * Recent in-session history reads retry an empty successful response using the profile's existing
 * retry count. Some MT5 terminals transiently return no bars while concurrent history requests are
 * being populated; shifted or stale non-empty history still fails the time-base check immediately.
 */
class Mt5MarketSource(
    private val profile: MT5BrokerProfile,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock(),
    private val symbolCalendars: SymbolCalendars = profile.symbolCalendars,
    private val retryBackoffMs: Long = 200L,
) : MarketSource,
    AutoCloseable {
    init {
        require(retryBackoffMs >= 0L) { "MT5 history retry backoff must not be negative" }
    }

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
        val fetcher =
            Mt5BarFetcher(
                profile.gatewayUrl,
                http,
                profile.serverTimeZone,
                normalizeBidBarsToMid = true,
                apiKey = profile.apiKey,
            )

        fun fetch(): List<Candle> =
            fetcher
                .fetchRange(wire, window, range)
                .map { candle -> candle.copy(symbol = symbol) }
                .toList()

        val recentNowMs = recentInSessionNowMs(bareSymbol, range)
        var candles = fetch()
        if (recentNowMs != null) {
            for (retry in 1..profile.retryAttempts) {
                if (candles.isNotEmpty()) break
                sleepBeforeRetry(retry)
                candles = fetch()
            }
        }
        validateRecentTimeBase(bareSymbol, wire, window, recentNowMs, candles)
        return candles.asSequence()
    }

    private fun recentInSessionNowMs(
        bareSymbol: String,
        range: TimeRange,
    ): Long? {
        val nowMs = clock.now()
        val recent = abs(range.to.toEpochMilli() - nowMs) <= RECENT_RANGE_TOLERANCE_MS
        val inSession = symbolCalendars.calendarFor(bareSymbol).isInSession(bareSymbol, Instant.ofEpochMilli(nowMs))
        return nowMs.takeIf { recent && inSession }
    }

    private fun sleepBeforeRetry(retry: Int) {
        val delayMs = retryBackoffMs * retry
        if (delayMs == 0L) return
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun validateRecentTimeBase(
        bareSymbol: String,
        wireSymbol: String,
        window: TimeWindow,
        recentNowMs: Long?,
        candles: List<Candle>,
    ) {
        val nowMs = recentNowMs ?: return
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
        // Lower bound is one bar window below zero, not zero: a bar closes on the time
        // boundary, so for a thin symbol the newest tick can legitimately predate the
        // just-closed bar's end by up to one window (no tick has printed in the new bar
        // yet). A genuine multi-hour time-base offset still lands far outside this band.
        val minAgeMs = -window.durationMs
        // Upper bound is measured in *session* time. Across a weekend or holiday the newest
        // closed bar honestly predates the first fresh tick by the whole gap — no bar could
        // have closed while the venue was shut — so only window slots the symbol's calendar
        // marks in session count against the allowance. A real server-clock offset still
        // fails: three hours of a trading day is 180 in-session 1m slots (#1055).
        val maxSlots = maxOf(3L, MIN_RECENT_BAR_AGE_MS / window.durationMs)
        val inSessionSlots = inSessionSlotsBetween(bareSymbol, window, newestClosedBarEndMs, tick.brokerTimeMs)
        require(barAgeMs >= minAgeMs && inSessionSlots <= maxSlots) {
            "MT5 time-base mismatch for $bareSymbol: " +
                "newest closed bar end=${Instant.ofEpochMilli(newestClosedBarEndMs)}, " +
                "tick=${Instant.ofEpochMilli(tick.brokerTimeMs)}, deltaMs=$barAgeMs, " +
                "inSessionSlots=$inSessionSlots (max $maxSlots); " +
                "set gateway MT5_SERVER_UTC_OFFSET_SECONDS=0 and verify server_time_zone=${profile.serverTimeZone.id}"
        }
    }

    /**
     * Number of [window]-sized slots starting in `[fromMs, toMs)` whose start the symbol's
     * calendar marks as in session — the bars that could actually have closed in that span.
     * Bounded by the span itself; a weekend of 1m slots is a few thousand cheap checks, once
     * per warmup fetch.
     */
    private fun inSessionSlotsBetween(
        bareSymbol: String,
        window: TimeWindow,
        fromMs: Long,
        toMs: Long,
    ): Long {
        if (toMs <= fromMs) return 0L
        val calendar = symbolCalendars.calendarFor(bareSymbol)
        var slots = 0L
        var slotStart = fromMs
        while (slotStart < toMs) {
            if (calendar.isInSession(bareSymbol, Instant.ofEpochMilli(slotStart))) slots += 1L
            slotStart += window.durationMs
        }
        return slots
    }

    override fun close() {}

    companion object {
        private const val RECENT_RANGE_TOLERANCE_MS: Long = 5 * 60_000L
        private const val MIN_RECENT_BAR_AGE_MS: Long = 5 * 60_000L
    }
}
