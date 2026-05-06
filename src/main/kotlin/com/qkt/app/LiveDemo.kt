package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.Strategy
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import java.time.Duration
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("LiveDemo")

fun main() {
    log.info("Starting qkt LiveDemo (TradingView -> LiveSession -> MockBroker)")

    val source = TradingViewMarketSource.connect(clock = SystemClock())

    val strategies: List<Pair<String, Strategy>> =
        listOf(
            "breakout-eurusd" to BreakoutOfYesterdayHighStrategy("OANDA:EURUSD", size = Money.of("1")),
        )
    val rules: List<RiskRule> =
        listOf(
            MaxPositionSize(symbol = "OANDA:EURUSD", maxQty = Money.of("3")),
            MaxPositionSize(symbol = "OANDA:XAUUSD", maxQty = Money.of("1")),
            MaxPositionSize(symbol = "BINANCE:BTCUSDT", maxQty = Money.of("1")),
        )

    val session =
        LiveSession(
            strategies = strategies,
            rules = rules,
            source = source,
            symbols = listOf("OANDA:EURUSD", "OANDA:XAUUSD", "BINANCE:BTCUSDT"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = SystemClock(),
            calendar = TradingCalendar.fxDefault(),
        )

    val handle = session.start()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutting down LiveSession...")
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
            source.close()
        },
    )

    log.info("LiveSession running. Press Ctrl-C to stop.")
    handle.awaitTermination(Duration.ofDays(365))
}
