package com.qkt.trade.session

import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.trade.BotIntent
import com.qkt.trade.BotTif
import com.qkt.trade.renderBotStrategy
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory

abstract class BotSessionParityFixture {
    // "/intent" compiles the rendered source through the DSL parser, which requires
    // BROKER:SYMBOL market-source syntax (renderBotStrategy emits `x = $qktSymbol
    // EVERY 1m`) — unlike the engine-level parity test, which submits Signal
    // objects directly and never touches the DSL, so a bare symbol works there.
    protected val symbol = "EXNESS:XAUUSD"
    protected val client = HttpClient.newHttpClient()

    protected fun ticks(): List<Tick> =
        (0 until 9).flatMap { bar ->
            val base = bar * 60_000L
            listOf(
                Tick(symbol, BigDecimal(2400 + bar), base + 1_000L),
                Tick(symbol, BigDecimal(2402 + bar), base + 30_000L),
            )
        } + Tick(symbol, BigDecimal("2413"), 9 * 60_000L + 1_000L)

    /** Decision script keyed by closed-bar count — identical for both arms. */
    protected fun decisions(): Map<Int, Side> = mapOf(3 to Side.BUY, 6 to Side.SELL)

    protected class GatedTickFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val permits = Semaphore(0)
        private val idx = AtomicInteger(0)

        fun release() = permits.release()

        fun releaseRemaining() = permits.release(ticks.size + 1)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i >= ticks.size) return null
            permits.acquire()
            return ticks[i]
        }

        override fun close() = Unit
    }

    protected class GatedSource(
        private val feed: GatedTickFeed,
    ) : MarketSource {
        override val name: String = "BotSessionServerParityFake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = feed
    }

    protected fun <T> withQuietLogs(block: () -> T): T {
        val names =
            listOf(
                "com.qkt.app.LiveSession",
                "com.qkt.execution.OrderManager",
                "com.qkt.app.TradingPipeline",
                "com.qkt.risk.RiskEngine",
            )
        val loggers = names.map { LoggerFactory.getLogger(it) as ch.qos.logback.classic.Logger }
        val previous = loggers.map { it.level }
        loggers.forEach { it.level = ch.qos.logback.classic.Level.ERROR }
        try {
            return block()
        } finally {
            loggers.zip(previous).forEach { (logger, level) -> logger.level = level }
        }
    }

    protected fun call(
        server: BotSessionServer,
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path"))
                .header("Authorization", "Bearer secret")
        if (method == "POST") {
            builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun intentSource(side: Side): String =
        renderBotStrategy(
            BotIntent(
                side = side,
                qktSymbol = symbol,
                lots = BigDecimal.ONE,
                sizingDsl = null,
                limitPrice = null,
                stopPrice = null,
                stopLimitPrice = null,
                sl = null,
                tp = null,
                tif = BotTif.GTC,
                expiresAtMs = null,
            ),
        ).replace("\n", "\\n")

    protected fun postIntent(
        server: BotSessionServer,
        side: Side,
    ) {
        val response =
            call(
                server,
                "POST",
                "/intent",
                """{"identity":"brain","source":"${intentSource(side)}"}""",
            )
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"queued\":true")
    }

    protected data class TradeKey(
        val symbol: String,
        val side: String,
        val quantity: BigDecimal,
        val price: BigDecimal,
        val timestamp: Long,
    )

    protected fun Trade.key(): TradeKey =
        TradeKey(
            symbol = symbol,
            side = side.toString(),
            quantity = quantity,
            price = price,
            timestamp = timestamp,
        )
}
