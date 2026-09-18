package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCommandRegimePortfolioTest : BacktestCommandPortfolioFixture() {
    @Test
    fun `regime portfolio backtest supports --bars`(
        @TempDir tmp: Path,
    ) {
        val dataRoot = tmp.resolve("data")
        seedTicks(dataRoot, days = 3)
        buildBars(dataRoot, "15m")

        Files.writeString(tmp.resolve("trend.qkt"), backtestChild("trend"))
        Files.writeString(tmp.resolve("meanrev.qkt"), backtestChild("meanrev"))
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1 CAPITAL 10000
            SYMBOLS
              gold = BACKTEST:XAUUSD EVERY 15m
            IMPORT 'trend.qkt'   AS trend
            IMPORT 'meanrev.qkt' AS meanrev
            REGIMES
              NAME regime
              STATE up   WHEN gold.close > gold.open
              STATE down DEFAULT
            ALLOCATE
              METHOD regime_weighted
              up   -> trend 0.8, meanrev 0.2
              down -> trend 0.2, meanrev 0.8
            RULES
              RUN trend
              RUN meanrev
            """.trimIndent(),
        )

        val (code, out) = runPortfolioBacktest(portfolio, dataRoot)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("\"perStrategy\":{")
        assertThat(out).contains("book:trend")
        assertThat(out).contains("book:meanrev")
    }

    @Test
    fun `regime portfolio with HOLD child flattens non-HOLD child on regime switch`(
        @TempDir tmp: Path,
    ) {
        val dataRoot = tmp.resolve("data")
        seedTicks(dataRoot, days = 3)
        buildBars(dataRoot, "15m")

        Files.writeString(
            tmp.resolve("trend.qkt"),
            backtestChild("trend"),
        )
        Files.writeString(
            tmp.resolve("meanrev.qkt"),
            backtestChild("meanrev"),
        )
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1 CAPITAL 10000
            SYMBOLS
              gold = BACKTEST:XAUUSD EVERY 15m
            IMPORT 'trend.qkt'   AS trend HOLD
            IMPORT 'meanrev.qkt' AS meanrev
            REGIMES
              NAME regime
              STATE up   WHEN gold.close > gold.open
              STATE down DEFAULT
            ALLOCATE
              METHOD regime_weighted
              up   -> trend 0.8, meanrev 0.2
              down -> trend 0.2, meanrev 0.8
            RULES
              WHEN gold.close > gold.open RUN trend
              WHEN gold.close <= gold.open RUN meanrev
            """.trimIndent(),
        )

        val (code, out) = runPortfolioBacktest(portfolio, dataRoot)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("\"perStrategy\":{")
        assertThat(out).contains("book:trend")
        assertThat(out).contains("book:meanrev")
    }

    @Test
    fun `regime portfolio backtest applies qkt config yaml book risk limits`(
        @TempDir tmp: Path,
    ) {
        val dataRoot = tmp.resolve("data")
        seedTicks(dataRoot, days = 3)
        buildBars(dataRoot, "15m")

        Files.writeString(tmp.resolve("trend.qkt"), backtestChild("trend"))
        Files.writeString(tmp.resolve("meanrev.qkt"), backtestChild("meanrev"))
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1 CAPITAL 10000
            SYMBOLS
              gold = BACKTEST:XAUUSD EVERY 15m
            IMPORT 'trend.qkt'   AS trend
            IMPORT 'meanrev.qkt' AS meanrev
            REGIMES
              NAME regime
              STATE up   WHEN gold.close > gold.open
              STATE down DEFAULT
            ALLOCATE
              METHOD regime_weighted
              up   -> trend 0.8, meanrev 0.2
              down -> trend 0.2, meanrev 0.8
            RULES
              RUN trend
              RUN meanrev
            """.trimIndent(),
        )
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            """
            book_risk:
              capital: "10000"
              limits:
                max_gross_exposure: "5.0"
              allocation:
                method: "REGIME_WEIGHTED"
                rebalance_every_bars: 1
            """.trimIndent(),
        )

        val (code, out) = runPortfolioBacktest(portfolio, dataRoot, extra = arrayOf("--config", config.toString()))
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("\"bookRisk\":{")
        assertThat(out).contains("book:trend")
        assertThat(out).contains("book:meanrev")
    }
}
