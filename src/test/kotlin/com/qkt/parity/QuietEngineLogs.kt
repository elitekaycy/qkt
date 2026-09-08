package com.qkt.parity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

/**
 * Silence the engine's per-order chatter for a test class.
 *
 * The order lifecycle logs a line per order at INFO, which is right in production and unusable in
 * a suite whose whole point is bursts of thirty to a hundred orders: one such class produced over
 * nine thousand log lines and tripped the build's test-log budget. Suites that place orders in
 * bulk call [silence] from `@BeforeAll` and [restore] from `@AfterAll`. Any test that asserts on
 * log output attaches its own appender and is unaffected by the level.
 */
object QuietEngineLogs {
    private val noisy = listOf("com.qkt.app.OrderManager", "com.qkt.app.TradingPipeline")
    private var previous: List<Level?> = emptyList()

    fun silence() {
        val loggers = noisy.map { LoggerFactory.getLogger(it) as Logger }
        previous = loggers.map { it.level }
        loggers.forEach { it.level = Level.WARN }
    }

    fun restore() {
        if (previous.isEmpty()) return
        noisy.forEachIndexed { index, name ->
            (LoggerFactory.getLogger(name) as Logger).level = previous[index]
        }
        previous = emptyList()
    }
}
