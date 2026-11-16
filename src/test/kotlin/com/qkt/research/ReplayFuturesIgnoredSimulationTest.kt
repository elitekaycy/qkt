package com.qkt.research

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.accounting.AccountingConfig
import com.qkt.backtest.BarFills
import com.qkt.backtest.ExecutionPreset
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.broker.continuous.ContinuousFixture
import com.qkt.broker.continuous.ContinuousFixture.Companion.ms
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** The futures exchange simulator models no latency, rejections or partial fills, and says so when they are asked for. */
class ReplayFuturesIgnoredSimulationTest {
    private val clock = FixedClock(time = ms("2024-09-20T00:00:00Z"))
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = ReplayBooks(ContinuousFixture().registry, AccountingConfig(), markTimestamp = { clock.time })

    private fun warningsFor(config: ExecutionSimulationConfig): List<String> {
        val logger = LoggerFactory.getLogger("com.qkt.research.ReplayFuturesRoutes") as Logger
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        val level = logger.level
        logger.level = Level.WARN
        logger.addAppender(logged)
        try {
            replayFuturesRoutes(
                config,
                bus,
                clock,
                books,
                BarFills.NONE,
                TradingCalendar.crypto(),
                listOf("BINANCE_UM:BTCUSDT@front"),
            )
        } finally {
            logger.detachAppender(logged)
            logger.level = level
        }
        return logged.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun `asking for latency on a futures run is warned about`() {
        assertThat(warningsFor(ExecutionSimulationConfig(preset = ExecutionPreset.MT5_BASIC, latencyMs = 50)))
            .containsExactly(
                "futures fill on the exchange simulator, which ignores latency, rejection and partial-fill settings",
            )
    }

    @Test
    fun `a plain futures run is not warned`() {
        assertThat(warningsFor(ExecutionSimulationConfig())).isEmpty()
    }
}
