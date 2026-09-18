package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin

/** Two-symbol tick data and strategies for the multi-symbol tick-resolved parity tests. */
internal object TickResolvedMultiSymbolFixtures {
    fun seedSym(
        dataRoot: Path,
        symbol: String,
        days: Int,
        base: Double,
        amp: Double,
        period: Double,
    ) {
        val start =
            LocalDate
                .parse("2024-01-02")
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        (0 until days * 1440)
            .map { m -> Tick(symbol, Money.of("%.3f".format(base + amp * sin(m / period))), start + m * 60_000L) }
            .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneOffset.UTC) }
            .forEach { (day, dayTicks) ->
                val f = dataRoot.resolve("symbols").resolve(symbol).resolve("$day.bin")
                Files.createDirectories(f.parent)
                BinaryTickWriter().write(f, symbol, dayTicks)
            }
    }

    fun twoSymbolStrategy(dir: Path): Path {
        val s = dir.resolve("two.qkt")
        Files.writeString(
            s,
            """
            STRATEGY two VERSION 1
            SYMBOLS
                a = BACKTEST:XAUUSD EVERY 15m
                b = BACKTEST:XAGUSD EVERY 15m
            RULES
                WHEN ema(a.close, 3) CROSSES ABOVE ema(a.close, 9) AND b.close > ema(b.close, 9)
                THEN BUY a SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema(a.close, 3) CROSSES BELOW ema(a.close, 9)
                THEN CLOSE a
            """.trimIndent(),
        )
        return s
    }

    fun bothTradeStrategy(dir: Path): Path {
        val s = dir.resolve("both.qkt")
        Files.writeString(
            s,
            """
            STRATEGY both VERSION 1
            SYMBOLS
                a = BACKTEST:XAUUSD EVERY 15m
                b = BACKTEST:XAGUSD EVERY 15m
            RULES
                WHEN ema(a.close, 3) CROSSES ABOVE ema(a.close, 9)
                THEN BUY a SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema(a.close, 3) CROSSES BELOW ema(a.close, 9) THEN CLOSE a
                WHEN ema(b.close, 3) CROSSES ABOVE ema(b.close, 9)
                THEN BUY b SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema(b.close, 3) CROSSES BELOW ema(b.close, 9) THEN CLOSE b
            """.trimIndent(),
        )
        return s
    }
}
