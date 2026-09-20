package com.qkt.cli

import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat

abstract class BacktestCommandPortfolioFixture {
    protected fun child(
        name: String,
        alias: String,
        symbol: String,
        timeframe: String = "1m",
    ) = """
        STRATEGY $name VERSION 1
        SYMBOLS
          $alias = EXNESS:$symbol EVERY $timeframe
        RULES
          WHEN $alias.close > 0 THEN BUY $alias SIZING 0.01
        """.trimIndent()

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
        symbol: String = "XAUUSD",
    ) {
        ticksFor(days)
            .map { it.copy(symbol = symbol) }
            .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneOffset.UTC) }
            .forEach { (day, dayTicks) ->
                val f = dataRoot.resolve("symbols").resolve(symbol).resolve("$day.bin")
                Files.createDirectories(f.parent)
                BinaryTickWriter().write(f, symbol, dayTicks)
            }
    }

    protected fun buildBars(
        dataRoot: Path,
        tf: String,
        symbol: String = "XAUUSD",
    ) {
        val code =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        symbol,
                        "--tf",
                        tf,
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-05",
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }

    protected fun backtestChild(
        name: String,
        symbol: String = "XAUUSD",
        timeframe: String = "15m",
    ) = """
        STRATEGY $name VERSION 1
        SYMBOLS
          gold = BACKTEST:$symbol EVERY $timeframe
        RULES
          WHEN gold.close > 0 THEN BUY gold SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
        """.trimIndent()

    protected fun runPortfolioBacktest(
        portfolio: Path,
        dataRoot: Path,
        extra: Array<String> = emptyArray(),
    ): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val orig = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                BacktestCommand(
                    Args(
                        arrayOf(
                            "backtest",
                            portfolio.toString(),
                            "--from",
                            "2024-01-02",
                            "--to",
                            "2024-01-05",
                            "--data-root",
                            dataRoot.toString(),
                            "--no-fetch",
                            "--allow-incomplete",
                            "--bars",
                            "--json",
                        ) + extra,
                    ),
                ).run()
            } finally {
                System.setOut(orig)
            }
        return code to out.toString()
    }

    protected fun bar(
        symbol: String,
        startTime: Long,
        durationMs: Long,
    ): Candle =
        Candle(
            symbol = symbol,
            open = BigDecimal("100"),
            high = BigDecimal("101"),
            low = BigDecimal("99"),
            close = BigDecimal("100"),
            volume = BigDecimal.ONE,
            startTime = startTime,
            endTime = startTime + durationMs,
        )
}
