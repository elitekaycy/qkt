package com.qkt.marketdata.live.bybit

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.live.LiveTickSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import okhttp3.OkHttpClient

/**
 * Shared base for Bybit public market data sources. [category] is `"spot"` or
 * `"linear"`; [prefix] is the symbol prefix this source matches (e.g. `BYBIT_SPOT:`).
 */
abstract class BybitMarketSource(
    private val category: String,
    private val prefix: String,
    private val wsUrl: String,
    private val restBase: String = BybitKlineClient.DEFAULT_BASE_URL,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock(),
    private val wsFactory: (String) -> BybitPublicWsLike = { url -> BybitPublicWs.connect(url) },
) : MarketSource,
    AutoCloseable {
    override val name: String = "Bybit:$category"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

    override fun supports(symbol: String): Boolean = symbol.startsWith(prefix)

    override fun liveTicks(symbols: List<String>): TickFeed {
        require(symbols.all { supports(it) }) { "$name cannot serve $symbols" }
        val wireSymbols = symbols.map { it.removePrefix(prefix) }
        return LiveTickFeed(
            source = BybitTickFeedSource(wsFactory, wsUrl, wireSymbols, clock),
            queueCapacity = 10_000,
        )
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        require(supports(symbol)) { "$name cannot serve $symbol" }
        val wire = symbol.removePrefix(prefix)
        return BybitKlineClient(restBase, category, http).fetchRange(wire, window, range)
    }

    override fun close() {}
}

class BybitSpotMarketSource(
    wsUrl: String = BybitPublicWs.SPOT_URL,
    restBase: String = BybitKlineClient.DEFAULT_BASE_URL,
    http: OkHttpClient = OkHttpClient(),
    clock: Clock = SystemClock(),
    wsFactory: (String) -> BybitPublicWsLike = { url -> BybitPublicWs.connect(url) },
) : BybitMarketSource(
        category = "spot",
        prefix = "BYBIT_SPOT:",
        wsUrl = wsUrl,
        restBase = restBase,
        http = http,
        clock = clock,
        wsFactory = wsFactory,
    )

class BybitLinearMarketSource(
    wsUrl: String = BybitPublicWs.LINEAR_URL,
    restBase: String = BybitKlineClient.DEFAULT_BASE_URL,
    http: OkHttpClient = OkHttpClient(),
    clock: Clock = SystemClock(),
    wsFactory: (String) -> BybitPublicWsLike = { url -> BybitPublicWs.connect(url) },
) : BybitMarketSource(
        category = "linear",
        prefix = "BYBIT_LINEAR:",
        wsUrl = wsUrl,
        restBase = restBase,
        http = http,
        clock = clock,
        wsFactory = wsFactory,
    )

private class BybitTickFeedSource(
    private val wsFactory: (String) -> BybitPublicWsLike,
    private val wsUrl: String,
    private val symbols: List<String>,
    private val clock: Clock,
) : LiveTickSource {
    private var ws: BybitPublicWsLike? = null
    private var client: BybitPublicWsClient? = null

    override fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
    ) {
        val w = wsFactory(wsUrl)
        ws = w
        val c = BybitPublicWsClient(w, clock)
        client = c
        c.subscribe(symbols, onTick = onTick, onDisconnect = onDisconnect)
    }

    override fun stop() {
        runCatching { client?.close() }
        runCatching { ws?.close() }
    }
}
