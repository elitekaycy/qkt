package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt backtest` and `qkt sweep` refuse a run whose setup is wrong, with one line saying what. */
class BacktestSetupErrorsTest : BacktestCommandFixture() {
    @TempDir
    lateinit var tmp: Path

    private val window =
        arrayOf("--from", "2024-01-15", "--to", "2024-01-17", "--data-root", "data/sample", "--no-fetch")

    @Test
    fun `a strategy that does not parse is reported line by line with a count`() {
        val broken =
            Files.writeString(
                tmp.resolve("broken.qkt"),
                "STRATEGY broken VERSION 1\nSYMBOLS\n  gold = BACKTEST:XAUUSD EVERY\n",
            )

        val (code, _, err) = runBacktest("backtest", broken.toString(), *window)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).containsPattern("broken\\.qkt:\\d+:\\d+ — ").containsPattern("\\d+ errors?\\n")
    }

    @Test
    fun `an instruments file that is not a map is a user error`() {
        val strategy = writeStrategy(tmp, "gold.close > 0")
        val instruments = Files.writeString(tmp.resolve("instruments.yaml"), "- a\n- b\n")

        val (code, _, err) =
            runBacktest(
                "backtest",
                strategy.toString(),
                *window,
                "--instruments",
                instruments.toString(),
            )

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("qkt: error: instruments.yaml: top-level must be a map")
    }

    @Test
    fun `symbols the strategy does not declare are refused`() {
        val strategy = writeStrategy(tmp, "gold.close > 0")

        val (code, _, err) = runBacktest("backtest", strategy.toString(), *window, "--symbols", "NOPE")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("--symbols contains unknown symbols [NOPE]; strategy declares ")
    }

    @Test
    fun `a starting balance that contradicts the portfolio capital is refused`() {
        val child =
            Files.writeString(
                tmp.resolve("ca.qkt"),
                "STRATEGY ca VERSION 1\nSYMBOLS\n  gold = EXNESS:XAUUSD EVERY 1m\nRULES\n  WHEN gold.close > 0 THEN BUY gold SIZING 0.01\n",
            )
        val book =
            Files.writeString(
                tmp.resolve("book.qkt"),
                "PORTFOLIO book VERSION 1 CAPITAL 10000\nIMPORT '${child.fileName}' AS ca\nRULES\n  RUN ca WEIGHT 1\n",
            )

        val (code, _, err) = runBacktest("backtest", book.toString(), *window, "--starting-balance", "5000")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("--starting-balance 5000 conflicts with portfolio CAPITAL 10000")

        val direct =
            Args(
                arrayOf(
                    "backtest",
                    book.toString(),
                    "--from",
                    "2024-01-15",
                    "--to",
                    "2024-01-17",
                    "--starting-balance",
                    "5000",
                ),
            )
        assertThatThrownBy {
            BacktestContext.buildPortfolio(
                direct,
                com.qkt.dsl.portfolio.PortfolioLoader
                    .load(book),
            )
        }.isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessageContaining("conflicts with portfolio CAPITAL 10000")
    }

    @Test
    fun `a backtest with no strategy at all is an argument error naming it`() {
        assertThatThrownBy { BacktestRunResolution.resolve(Args(arrayOf("backtest")), Config()) }
            .isInstanceOf(ArgError::class.java)
            .hasMessageContaining("missing required argument: <strategy.qkt>")
    }
}
