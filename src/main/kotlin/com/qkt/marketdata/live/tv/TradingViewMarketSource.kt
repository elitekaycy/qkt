package com.qkt.marketdata.live.tv

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
import com.qkt.marketdata.source.UnsupportedDataException

class TradingViewMarketSource(
    private val webSocket: TradingViewWebSocketLike,
    private val clock: Clock = SystemClock(),
    private val queueCapacity: Int = 10_000,
    private val chartSessionFactory: (TradingViewWebSocketLike) -> TradingViewChartSession = { ws ->
        TradingViewChartSession(ws)
    },
) : MarketSource,
    AutoCloseable {
    override val name: String = "TradingView"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

    override fun supports(symbol: String): Boolean = SYMBOL_REGEX.matches(symbol)

    override fun liveTicks(symbols: List<String>): TickFeed {
        require(symbols.all { supports(it) }) {
            "TradingView symbols must match EXCHANGE:SYMBOL form: $symbols"
        }
        val source: LiveTickSource = TradingViewLiveTickSource(webSocket, clock, symbols)
        return LiveTickFeed(source = source, queueCapacity = queueCapacity)
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        require(supports(symbol)) {
            "TradingView symbol must match EXCHANGE:SYMBOL form: $symbol"
        }
        val resolution = TradingViewResolution.fromTimeWindow(window)
        val totalMs = range.to.toEpochMilli() - range.from.toEpochMilli()
        val rawCount = (totalMs + window.durationMs - 1) / window.durationMs
        val count = rawCount.toInt().coerceAtLeast(1)
        val toSeconds = range.to.epochSecond
        val candles =
            chartSessionFactory(webSocket).getBars(
                symbol = symbol,
                resolution = resolution,
                count = count,
                toTimestampSeconds = toSeconds,
            )
        return candles
            .asSequence()
            .filter { it.startTime >= range.from.toEpochMilli() && it.startTime < range.to.toEpochMilli() }
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> =
        throw UnsupportedDataException(
            MarketSourceCapability.TICKS,
            "TradingViewMarketSource does not expose tick history; use bars()",
        )

    override fun close() {
        webSocket.close()
    }

    private class TradingViewLiveTickSource(
        private val ws: TradingViewWebSocketLike,
        private val clock: Clock,
        private val symbols: List<String>,
    ) : LiveTickSource {
        private var session: TradingViewQuoteSession? = null

        override fun start(
            onTick: (Tick) -> Unit,
            onError: (Throwable) -> Unit,
            onDisconnect: () -> Unit,
            onReconnect: () -> Unit,
        ) {
            val qs = TradingViewQuoteSession(ws, clock)
            session = qs
            qs.subscribe(symbols, onTick, onError, onDisconnect)
        }

        override fun stop() {
            session?.close()
            session = null
        }
    }

    companion object {
        private val SYMBOL_REGEX = Regex("^[A-Z0-9]+:[A-Z0-9_]+$")

        fun connect(
            url: String = TradingViewWebSocket.DEFAULT_URL,
            origin: String = TradingViewWebSocket.DEFAULT_ORIGIN,
            authToken: String = TradingViewWebSocket.ANONYMOUS_TOKEN,
            clock: Clock = SystemClock(),
        ): TradingViewMarketSource =
            TradingViewMarketSource(
                webSocket = TradingViewWebSocket.connect(url = url, origin = origin, authToken = authToken),
                clock = clock,
            )
    }
}
