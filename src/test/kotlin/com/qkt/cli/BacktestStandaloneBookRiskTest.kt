package com.qkt.cli

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Live enforces `book_risk` only for PORTFOLIO deployments: a standalone strategy is deployed
 * through StrategyHandle, which never builds a BookRiskController (catalog row A23). A standalone
 * backtest must therefore run identically with or without a `book_risk` block, or it rejects
 * orders a live deploy of the same file would send.
 */
class BacktestStandaloneBookRiskTest {
    @Test
    fun `standalone backtest ignores book_risk exactly as a live deploy does`(
        @TempDir dir: Path,
    ) {
        val unbounded = run(dir.resolve("plain"), bookRisk = false)
        val bounded = run(dir.resolve("bounded"), bookRisk = true)

        assertThat(unbounded.trades).isNotEmpty()
        assertThat(bounded.trades).isEqualTo(unbounded.trades)
        assertThat(bounded.global.totalPnL).isEqualByComparingTo(unbounded.global.totalPnL)
    }

    @Test
    fun `standalone backtest warns that book_risk limits are not enforced`(
        @TempDir dir: Path,
    ) {
        val err = ByteArrayOutputStream()
        val saved = System.err
        System.setErr(PrintStream(err, true))
        try {
            run(dir, bookRisk = true)
        } finally {
            System.setErr(saved)
        }

        assertThat(err.toString()).contains("book_risk limits are configured but are enforced only for PORTFOLIO")
    }

    private fun run(
        dir: Path,
        bookRisk: Boolean,
    ): com.qkt.backtest.BacktestResult {
        Files.createDirectories(dir)
        val strat = dir.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1
                WHEN ema(gold.close, 3) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        val config = dir.resolve("qkt.config.yaml")
        val base =
            """
            source: backtest
            data_root: ./data
            starting_balance: 10000
            brokers:
              backtest:
                type: paper
            """.trimIndent()
        val limits =
            """
            book_risk:
              capital: "10000"
              limits:
                max_gross_exposure: "0.0001"
            """.trimIndent()
        Files.writeString(config, if (bookRisk) base + "\n" + limits else base)
        val ast = (Dsl.parseFile(strat) as ParseResult.Success).value
        val argv =
            arrayOf(
                "backtest",
                strat.toString(),
                "--from",
                "2026-06-04",
                "--to",
                "2026-06-05",
                "--data-root",
                dir.resolve("data").toString(),
                "--config",
                config.toString(),
            )
        val ctx = BacktestContext.build(Args(argv), ast, fetcherOverride = FakeXauFetcher)
        ctx.provision()
        return ctx.backtest(emptyMap()).run()
    }
}
