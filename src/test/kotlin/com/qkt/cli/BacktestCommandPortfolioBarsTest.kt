package com.qkt.cli

import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCommandPortfolioBarsTest : BacktestCommandPortfolioFixture() {
    @Test
    fun `portfolio backtest supports --bars`(
        @TempDir tmp: Path,
    ) {
        val dataRoot = tmp.resolve("data")
        seedTicks(dataRoot, days = 3)
        buildBars(dataRoot, "15m")

        Files.writeString(tmp.resolve("child.qkt"), backtestChild("child"))
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS child
            RULES
              RUN child
            """.trimIndent(),
        )

        val (code, out) = runPortfolioBacktest(portfolio, dataRoot)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("\"perStrategy\":{")
        assertThat(out).contains("book:child")
    }

    @Test
    fun `portfolio backtest supports --bars --tick-fills`(
        @TempDir tmp: Path,
    ) {
        val dataRoot = tmp.resolve("data")
        seedTicks(dataRoot, days = 3)
        buildBars(dataRoot, "15m")

        Files.writeString(tmp.resolve("child.qkt"), backtestChild("child"))
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS child
            RULES
              RUN child
            """.trimIndent(),
        )

        val (code, out) = runPortfolioBacktest(portfolio, dataRoot, extra = arrayOf("--tick-fills"))
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("\"perStrategy\":{")
        assertThat(out).contains("book:child")
    }

    @Test
    fun `portfolio backtest --bar-tf rejects a tf that does not divide the child timeframe`(
        @TempDir tmp: Path,
    ) {
        val dataRoot = tmp.resolve("data")
        seedTicks(dataRoot, days = 3)
        buildBars(dataRoot, "15m")

        Files.writeString(tmp.resolve("child.qkt"), backtestChild("child"))
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS child
            RULES
              RUN child
            """.trimIndent(),
        )

        val (code, _) = runPortfolioBacktest(portfolio, dataRoot, extra = arrayOf("--bar-tf", "2m"))
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `portfolio backtest replays each symbol at its finest declared timeframe`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("ca.qkt"), child("ca", "gold", "XAUUSD"))
        Files.writeString(tmp.resolve("cb.qkt"), child("cb", "eur", "EURUSD", timeframe = "5m"))
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1
            IMPORT 'ca.qkt' AS ca
            IMPORT 'cb.qkt' AS cb
            RULES
              RUN ca
              RUN cb
            """.trimIndent(),
        )
        val start = Instant.parse("2026-07-10T00:00:00Z").toEpochMilli()
        val day = LocalDate.parse("2026-07-10")
        val store = LocalBarStore(tmp)
        store.writeDay("EXNESS", "XAUUSD", "1m", day, listOf(bar("EXNESS:XAUUSD", start, 60_000L)))
        store.writeDay("EXNESS", "EURUSD", "5m", day, listOf(bar("EXNESS:EURUSD", start, 300_000L)))

        val args =
            Args(
                arrayOf(
                    "backtest",
                    portfolio.toString(),
                    "--from",
                    "2026-07-10",
                    "--to",
                    "2026-07-11",
                    "--data-root",
                    tmp.toString(),
                    "--no-fetch",
                    "--json",
                ),
            )

        assertThat(BacktestCommand(args).run()).isEqualTo(ExitCodes.SUCCESS)
    }
}
