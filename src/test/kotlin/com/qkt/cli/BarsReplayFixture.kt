package com.qkt.cli

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin

abstract class BarsReplayFixture {
    protected fun aggregate(
        ticks: List<Tick>,
        tf: String,
    ): List<Candle> {
        val out = ArrayList<Candle>()
        val agg = CandleAggregator.standalone(TimeWindow.parse(tf)) { out.add(it) }
        ticks.forEach { agg.onTick(it) }
        agg.flushClosed(Long.MAX_VALUE)
        return out
    }

    protected fun ticksFor(days: Int): List<Tick> {
        val start =
            LocalDate
                .parse("2024-01-02")
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        return (0 until days * 1440).map { m ->
            val mid = 1850.0 + 8.0 * sin(m / 40.0)
            Tick("XAUUSD", Money.of("%.3f".format(mid)), start + m * 60_000L)
        }
    }

    protected fun seedTicks(
        dataRoot: Path,
        days: Int,
    ) {
        ticksFor(days)
            .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneOffset.UTC) }
            .forEach { (day, dayTicks) ->
                val f = dataRoot.resolve("symbols").resolve("XAUUSD").resolve("$day.bin")
                Files.createDirectories(f.parent)
                BinaryTickWriter().write(f, "XAUUSD", dayTicks)
            }
    }

    protected fun strategyFile(dir: Path): Path {
        val s = dir.resolve("s.qkt")
        Files.writeString(
            s,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 15m
            RULES
                WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema(gold.close, 3) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        return s
    }
}
