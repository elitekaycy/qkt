package com.qkt.backtest

import com.qkt.backtest.report.BacktestReportWriter
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A futures run's report carries its roll and contract files, listed where every artifact is listed. */
class FuturesReportBundleTest {
    @Test
    fun `a continuous futures report writes and lists rolls and contract fills`(
        @TempDir dir: Path,
    ) {
        val (result, _) =
            FuturesFixtureRun.run(
                dir,
                "btcusdt-rolls",
                """
                STRATEGY hold VERSION 1
                SYMBOLS
                    btc = BINANCE_UM:BTCUSDT@front EVERY 15m
                RULES
                    WHEN btc.close > 0
                    THEN BUY btc SIZING 0.01 EXIT AFTER 2d
                """,
                from = "2024-09-18",
                to = "2024-09-21",
            )
        val report = Files.createDirectories(dir.resolve("report"))

        BacktestReportWriter(report).write(result)

        assertThat(Files.readAllLines(report.resolve("rolls.csv"))).hasSize(2)
        assertThat(Files.readAllLines(report.resolve("contracts.csv"))).hasSize(3)
        assertThat(report.resolve("settlements.csv")).doesNotExist()
        assertThat(
            Files.readString(report.resolve("manifest.json")),
        ).contains("\"rolls.csv\"").contains("\"contracts.csv\"")
        assertThat(Files.readString(report.resolve("result.json")))
            .contains("\"rollsCsv\": \"rolls.csv\"")
            .contains("\"contractsCsv\": \"contracts.csv\"")
            .contains("\"rollCostsPaid\"")
        assertThat(result.global.rollCostsPaid).isEqualByComparingTo(result.rolls.single().cost)
    }
}
