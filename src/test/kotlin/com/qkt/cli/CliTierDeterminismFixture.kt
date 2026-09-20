package com.qkt.cli

import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat

abstract class CliTierDeterminismFixture {
    protected fun seedTicks(
        dataRoot: Path,
        symbol: String,
        base: Double,
        amplitude: Double,
        period: Double,
    ) {
        val day = LocalDate.parse("2024-01-02")
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ticks =
            (0 until 1440).map { minute ->
                val price = base + amplitude * sin(minute / period)
                Tick(symbol, Money.of("%.3f".format(price)), start + minute * 60_000L)
            }
        val target = dataRoot.resolve("symbols/$symbol/$day.bin")
        Files.createDirectories(target.parent)
        BinaryTickWriter().write(target, symbol, ticks)
    }

    protected fun buildBars(
        dataRoot: Path,
        symbol: String,
    ) {
        val code =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        symbol,
                        "--tf",
                        "15m",
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-03",
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }

    protected fun strategyFile(
        dir: Path,
        fileName: String,
        strategyName: String,
        symbol: String,
        alias: String,
    ): Path {
        val path = dir.resolve(fileName)
        Files.writeString(
            path,
            """
            STRATEGY $strategyName VERSION 1
            SYMBOLS
                $alias = BACKTEST:$symbol EVERY 15m
            RULES
                WHEN ema($alias.close, 3) CROSSES ABOVE ema($alias.close, 9)
                THEN BUY $alias SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema($alias.close, 3) CROSSES BELOW ema($alias.close, 9)
                THEN CLOSE $alias
            """.trimIndent(),
        )
        return path
    }

    protected fun portfolioFile(dir: Path): Path {
        strategyFile(dir, "gold-child.qkt", "gold_child", "XAUUSD", "gold")
        strategyFile(dir, "silver-child.qkt", "silver_child", "XAGUSD", "silver")
        return dir.resolve("book.qkt").also { path ->
            Files.writeString(
                path,
                """
                PORTFOLIO deterministic_book VERSION 1
                IMPORT 'gold-child.qkt' AS gold
                IMPORT 'silver-child.qkt' AS silver
                RULES
                    RUN gold
                    RUN silver
                """.trimIndent(),
            )
        }
    }

    protected fun instrumentsFile(dir: Path): Path =
        dir.resolve("instruments.yaml").also { path ->
            Files.writeString(
                path,
                """
                instruments:
                  - qktSymbol: BACKTEST:XAUUSD
                    contractSize: 100
                    volumeStep: 0.01
                    volumeMin: 0.01
                    pointSize: 0.001
                    digits: 3
                    tradeStopsLevelPoints: 0
                """.trimIndent(),
            )
        }
}
