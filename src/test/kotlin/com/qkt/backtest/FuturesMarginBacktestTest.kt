package com.qkt.backtest

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A futures backtest refuses the entries its account could not margin (`btcusdt-rolls`, ~60000 USDT per BTC). */
class FuturesMarginBacktestTest {
    private val strategy =
        """
        STRATEGY margined VERSION 1
        SYMBOLS
            btc = BINANCE_UM:BTCUSDT@front EVERY 15m
        RULES
            WHEN btc.close > 0
            THEN BUY btc SIZING 0.01 EXIT AFTER 2d
        """

    private fun run(
        dir: Path,
        balance: String,
    ) = FuturesFixtureRun
        .run(
            dir,
            "btcusdt-rolls",
            strategy,
            from = "2024-09-18",
            to = "2024-09-21",
            rootLines = "    margin: { initial: 0.5, maintenance: 0.25, basis: notional }\n",
            flags = listOf("--starting-balance", balance),
        ).first

    @Test
    fun `an entry the account cannot margin is refused`(
        @TempDir dir: Path,
    ) {
        val result = run(dir, "100")

        assertThat(result.trades).isEmpty()
        assertThat(result.rejections).isNotEmpty
        assertThat(result.rejections.map { it.reason }).allSatisfy {
            assertThat(it).contains("initial margin").contains("account equity 100")
        }
    }

    @Test
    fun `an account that can margin the entry trades it`(
        @TempDir dir: Path,
    ) {
        assertThat(run(dir, "10000").trades).hasSize(2)
    }

    @Test
    fun `a margined run reports each day's margin`(
        @TempDir dir: Path,
    ) {
        val days = run(dir, "10000").marginDaily

        assertThat(days.map { it.date.toString() }).contains("2024-09-18", "2024-09-19")
        assertThat(days).allSatisfy { assertThat(it.marginUsed).isGreaterThan(it.maintenance) }
        assertThat(days.none { it.marginCall }).isTrue()
    }

    @Test
    fun `the report writes the daily margin file and lists it`(
        @TempDir dir: Path,
    ) {
        val result = run(dir, "10000")
        val report =
            java.nio.file.Files
                .createDirectories(dir.resolve("report"))

        com.qkt.backtest.report
            .BacktestReportWriter(report)
            .write(result)

        val lines =
            java.nio.file.Files
                .readAllLines(report.resolve("margin_daily.csv"))
        assertThat(lines.first()).isEqualTo("date,marginUsed,maintenance,equity,marginCall")
        assertThat(lines).hasSize(result.marginDaily.size + 1)
        assertThat(
            java.nio.file.Files
                .readString(report.resolve("manifest.json")),
        ).contains("\"margin_daily.csv\"")
    }
}
