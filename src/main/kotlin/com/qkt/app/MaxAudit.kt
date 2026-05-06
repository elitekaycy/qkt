package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.WarmupSpec
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("MaxAudit")

private val ALL_SYMBOLS =
    listOf(
        "OANDA:XAUUSD",
        "OANDA:EURUSD",
        "OANDA:GBPUSD",
        "BINANCE:BTCUSDT",
        "BINANCE:ETHUSDT",
        "NASDAQ:AAPL",
        "NASDAQ:TSLA",
    )

fun main() {
    log.info("=== qkt MaxAudit: real TradingView, all asset classes ===")
    log.info("Symbols: {}", ALL_SYMBOLS)

    val source = TradingViewMarketSource.connect()
    try {
        val workingForBars = phaseBars(source)
        val workingForLive = phaseLiveTicks(source)
        val strategySymbols = workingForLive.intersect(workingForBars.toSet()).toList()
        phaseStrategy(source, strategySymbols)
    } finally {
        source.close()
        log.info("=== MaxAudit complete ===")
    }
}

private fun phaseBars(source: TradingViewMarketSource): List<String> {
    log.info("--- Phase A: BARS query per symbol (last 2h of 5m bars) ---")
    val now = Instant.now()
    val range = TimeRange(now.minus(Duration.ofHours(2)), now)
    val ok = mutableListOf<String>()
    for (symbol in ALL_SYMBOLS) {
        try {
            val bars = source.bars(symbol, TimeWindow.FIVE_MINUTES, range).toList()
            log.info(
                "  {}: {} bars (lastClose={}, firstStart={})",
                symbol,
                bars.size,
                bars.lastOrNull()?.close,
                bars.firstOrNull()?.startTime,
            )
            if (bars.isNotEmpty()) ok.add(symbol)
        } catch (e: Exception) {
            val causeChain =
                generateSequence(e as Throwable?) { it.cause }
                    .joinToString(" <- ") { "${it::class.java.simpleName}(${it.message})" }
            log.warn("  {}: bars FAILED -> {}", symbol, causeChain)
        }
    }
    log.info("  Phase A summary: {} of {} symbols delivered bars", ok.size, ALL_SYMBOLS.size)
    return ok
}

private fun phaseLiveTicks(source: TradingViewMarketSource): List<String> {
    log.info("--- Phase B: LIVE TICKS subscription (45s window) ---")
    val feed = source.liveTicks(ALL_SYMBOLS)
    val counts = mutableMapOf<String, Int>()
    val deadline = System.currentTimeMillis() + 45_000L
    try {
        while (System.currentTimeMillis() < deadline) {
            val tick = feed.next() ?: break
            counts.merge(tick.symbol, 1) { a, _ -> a + 1 }
        }
    } finally {
        feed.close()
    }
    for (symbol in ALL_SYMBOLS) {
        log.info("  {}: {} ticks", symbol, counts.getOrDefault(symbol, 0))
    }
    val ok = counts.filterValues { it > 0 }.keys.toList()
    log.info("  Phase B summary: {} of {} symbols delivered live ticks", ok.size, ALL_SYMBOLS.size)
    return ok
}

private fun phaseStrategy(
    source: TradingViewMarketSource,
    symbols: List<String>,
) {
    log.info("--- Phase C: cross-asset strategy LIVE (90s) ---")
    if (symbols.isEmpty()) {
        log.warn("  No symbols qualified for both bars + live; skipping strategy phase.")
        return
    }
    log.info("  Strategy symbols: {}", symbols)
    val strategy = MaxAuditStrategy(symbols)
    val handle =
        LiveSession(
            strategies = listOf(strategy),
            rules = symbols.map { MaxPositionSize(it, Money.of("1")) },
            source = source,
            symbols = symbols,
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = SystemClock(),
            calendar = TradingCalendar.fxDefault(),
            warmupOverride = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 60),
        ).start()
    log.info("  LiveSession started; observing for 90s...")
    handle.awaitTermination(Duration.ofSeconds(90))
    handle.stop()
    handle.awaitTermination(Duration.ofSeconds(5))
    strategy.printDiag()
    log.info("  Phase C summary: {} trades observed", handle.recentTrades().size)
}
