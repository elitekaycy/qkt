package com.qkt.parity

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/** Candles, balances and log quieting shared by the generated reentry parity tests. */
internal object ReentryReplayFixtures {
    fun <T> withQuietParityLogs(block: () -> T): T {
        val loggers =
            listOf(
                "com.qkt.app.TradingPipeline",
                "com.qkt.app.OrderManager",
                "com.qkt.app.LiveSession",
                "com.qkt.app.PerStreamWarmupCoordinator",
                "qkt.trade",
            ).map { LoggerFactory.getLogger(it) as ch.qos.logback.classic.Logger }
        val previousLevels = loggers.associateWith { it.level }
        loggers.forEach { it.level = ch.qos.logback.classic.Level.WARN }
        return try {
            block()
        } finally {
            previousLevels.forEach { (logger, level) -> logger.level = level }
        }
    }

    fun dailyHaltResetCandles(): Map<String, List<Candle>> =
        mapOf(
            "BACKTEST:X" to
                listOf(
                    candle("100", DAY_MS - 10 * ONE_MINUTE_MS),
                    candle("101", DAY_MS - 9 * ONE_MINUTE_MS),
                    candle("90", DAY_MS - 8 * ONE_MINUTE_MS),
                    candle("91", DAY_MS - 7 * ONE_MINUTE_MS),
                    candle("110", DAY_MS - 6 * ONE_MINUTE_MS),
                    candle("111", DAY_MS - 5 * ONE_MINUTE_MS),
                    candle("120", DAY_MS + ONE_MINUTE_MS),
                    candle("121", DAY_MS + 2 * ONE_MINUTE_MS),
                    candle("140", DAY_MS + 3 * ONE_MINUTE_MS),
                    candle("141", DAY_MS + 4 * ONE_MINUTE_MS),
                ),
        )

    fun candle(
        close: String,
        startTime: Long,
    ): Candle =
        Candle(
            symbol = "BACKTEST:X",
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ONE,
            startTime = startTime,
            endTime = startTime + ONE_MINUTE_MS,
        )

    val STARTING_BALANCE: BigDecimal = BigDecimal("1000")
    val REENTRY_PRICES: List<String> = listOf("100", "101", "102", "90", "90", "105", "106", "90")
    const val TEN_MINUTES_MS: Long = 10 * 60 * 1000L
    const val NO_HALT_EXPECTED: String = "__none__"
    const val ONE_MINUTE_MS: Long = 60_000L
    const val DAY_MS: Long = 86_400_000L
}
