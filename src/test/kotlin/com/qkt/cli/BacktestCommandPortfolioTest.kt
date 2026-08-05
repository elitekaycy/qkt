package com.qkt.cli

import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.store.LocalBarStore
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
        timeframe: String = "1m",
    ) = """
        STRATEGY $name VERSION 1
        SYMBOLS
          $alias = EXNESS:$symbol EVERY $timeframe
        RULES
          WHEN $alias.close > 0 THEN BUY $alias SIZING 0.01
        """.trimIndent()

    private fun ticksFor(days: Int): List<Tick> {
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

    private fun seedTicks(
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

    private fun buildBars(
        dataRoot: Path,
        tf: String,
    ) {
        val code =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        "XAUUSD",
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

    private fun backtestChild(
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

    private fun runPortfolioBacktest(
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

    private fun bar(
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
