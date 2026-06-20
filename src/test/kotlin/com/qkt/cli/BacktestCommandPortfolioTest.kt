package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end §8 check: `qkt backtest <portfolio.qkt>` runs the children as N attributed strategies on
 * the repo's real sample data with the book-risk layer from config, and the `--json` output carries
 * the full dataset (per-strategy attribution, book analytics, book-risk series).
 */
class BacktestCommandPortfolioTest {
    private fun child(
        name: String,
        alias: String,
        symbol: String,
    ) = """
        STRATEGY $name VERSION 1
        SYMBOLS
          $alias = EXNESS:$symbol EVERY 1m
        RULES
          WHEN $alias.close > 0 THEN BUY $alias SIZING 0.01
        """.trimIndent()

    @Test
    fun `portfolio backtest on sample data emits per-strategy + book data`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("ca.qkt"), child("ca", "gold", "XAUUSD"))
        Files.writeString(tmp.resolve("cb.qkt"), child("cb", "eur", "EURUSD"))
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
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            """
            book_risk:
              capital: "10000"
              limits:
                max_gross_exposure: "20.0"
              allocation:
                method: "INVERSE_VOL"
                rebalance_every_bars: 5
            """.trimIndent(),
        )

        val args =
            Args(
                arrayOf(
                    "backtest",
                    portfolio.toString(),
                    "--from",
                    "2024-01-15",
                    "--to",
                    "2024-01-17",
                    "--data-root",
                    "data/sample",
                    "--no-fetch",
                    "--allow-incomplete",
                    "--config",
                    config.toString(),
                    "--json",
                    "--starting-balance",
                    "10000",
                ),
            )

        val captured = ByteArrayOutputStream()
        val orig = System.out
        System.setOut(PrintStream(captured))
        val code =
            try {
                BacktestCommand(args).run()
            } finally {
                System.setOut(orig)
            }

        val out = captured.toString()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("\"perStrategy\":{")
        assertThat(out).contains("book:ca")
        assertThat(out).contains("book:cb")
        assertThat(out).contains("\"bookAnalytics\":{")
        assertThat(out).contains("\"bookRisk\":{")
    }
}
