package com.qkt.parity

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/** The shared symbol and start time, a tick-list market source and log quieting for the backtest/live parity tests. */
internal object BacktestLiveParityFixtures {
    val symbol = "BTCUSDT"
    val initialTs = 1_700_000_000_000L

    fun <T> withQuietParityLogs(block: () -> T): T {
        val loggers =
            listOf(
                "com.qkt.app.LiveSession",
                "com.qkt.app.OrderManager",
                "com.qkt.app.TradingPipeline",
                "com.qkt.risk.RiskEngine",
            ).map { LoggerFactory.getLogger(it) as ch.qos.logback.classic.Logger }
        val previous = loggers.map { it.level }
        loggers.forEach { it.level = ch.qos.logback.classic.Level.ERROR }
        return try {
            block()
        } finally {
            loggers.zip(previous).forEach { (logger, level) -> logger.level = level }
        }
    }

    // The engine clock is driven by the tick being PROCESSED (LiveSession advances a MutableClock in
    // its consumer loop), so the feed just returns ticks — it no longer touches the clock.
    class TickListFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            return if (i >= ticks.size) null else ticks[i]
        }

        override fun close() = Unit
    }

    class FakeSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "ParityFake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = TickListFeed(ticks)
    }
}
