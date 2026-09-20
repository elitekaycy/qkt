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
class BacktestCommandPortfolioTest : BacktestCommandPortfolioFixture() {
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
            PORTFOLIO book VERSION 1 CAPITAL 10000
            IMPORT 'ca.qkt' AS ca
            IMPORT 'cb.qkt' AS cb
            RULES
              RUN ca WEIGHT 0.6
              RUN cb WEIGHT 0.4
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

    @Test
    fun `portfolio child with a two-stream condition still trades`(
        @TempDir tmp: Path,
    ) {
        // Regression: a child whose WHEN references TWO streams (a sync-group condition) placed
        // zero trades when run as a portfolio member, while the identical strategy traded fine
        // standalone and a single-stream-condition sibling traded fine in the book. The condition
        // here is trivially true on every aligned close, so any zero-trade result means the
        // synced rule never evaluated at all inside the portfolio.
        val dataRoot = tmp.resolve("data")
        seedTicks(dataRoot, days = 3)
        seedTicks(dataRoot, days = 3, symbol = "XAGUSD")
        buildBars(dataRoot, "15m")
        buildBars(dataRoot, "15m", symbol = "XAGUSD")

        Files.writeString(
            tmp.resolve("dual.qkt"),
            """
            STRATEGY dual VERSION 1
            SYMBOLS
              gold = BACKTEST:XAUUSD EVERY 15m,
              silver = BACKTEST:XAGUSD EVERY 15m
            RULES
              WHEN gold.close > 0 AND silver.close > 0 AND POSITION.gold = 0
              THEN BUY gold SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
            """.trimIndent(),
        )
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1
            IMPORT 'dual.qkt' AS dual
            RULES
              RUN dual
            """.trimIndent(),
        )

        // Control: the identical strategy standalone MUST trade — pins that any book-side zero
        // is portfolio wiring, not the strategy/data.
        val (soloCode, soloOut) = runPortfolioBacktest(tmp.resolve("dual.qkt"), dataRoot)
        assertThat(soloCode).isEqualTo(ExitCodes.SUCCESS)
        val soloTrades =
            Regex("\"trades\":(\\d+)")
                .find(soloOut)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        assertThat(soloTrades)
            .withFailMessage("standalone control did not trade; out tail: %s", soloOut.takeLast(300))
            .isNotNull()
            .isGreaterThan(0)

        val (code, out) = runPortfolioBacktest(portfolio, dataRoot)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val tradeCount =
            Regex("\"book:dual\":\\{[^}]*\"tradeCount\":(\\d+)")
                .find(out)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        assertThat(tradeCount)
            .withFailMessage("two-stream-condition child placed no trades in the book")
            .isNotNull()
            .isGreaterThan(0)
    }
}
