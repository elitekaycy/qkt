package com.qkt.backtest

import com.qkt.common.Side
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A short on the continuous stream of `btcusdt-rolls` (BTC rose from about 60,000 over 2024-09-18..21)
 * with maintenance close to initial margin and just enough equity for the entry: the rise takes
 * equity below maintenance and the stream's venue liquidates it.
 */
class ContinuousLiquidationBacktestTest {
    private val strategy =
        """
        STRATEGY shorted VERSION 1
        SYMBOLS
            btc = BINANCE_UM:BTCUSDT@front EVERY 15m
        RULES
            WHEN btc.close > 0
            THEN SELL btc SIZING 0.01 EXIT AFTER 2d
        """

    private fun run(dir: Path) =
        FuturesFixtureRun.run(
            dir,
            "btcusdt-rolls",
            strategy,
            from = "2024-09-18",
            to = "2024-09-21",
            rootLines = "    margin: { initial: 0.5, maintenance: 0.49, basis: notional }\n",
            flags = listOf("--starting-balance", "320"),
        )

    @Test
    fun `a stream position below maintenance is liquidated on the stream`(
        @TempDir dir: Path,
    ) {
        val result = run(dir).first

        val liquidation = result.liquidations.first()
        assertThat(liquidation.symbol).isEqualTo("BINANCE_UM:BTCUSDT@front")
        assertThat(liquidation.side).isEqualTo(Side.BUY)
        assertThat(liquidation.equity).isLessThan(liquidation.maintenance)
        assertThat(
            result.contractFills.map { it.contract },
        ).allSatisfy { assertThat(it).startsWith("BINANCE_UM:BTCUSDT_") }
    }

    @Test
    fun `the report writes liquidations csv and indexes it in result json`(
        @TempDir dir: Path,
    ) {
        val result = run(dir).first
        val report = Files.createDirectories(dir.resolve("report"))

        com.qkt.backtest.report
            .BacktestReportWriter(report)
            .write(result)

        val lines = Files.readAllLines(report.resolve("liquidations.csv"))
        assertThat(lines.first()).isEqualTo("timestamp,strategy,symbol,side,quantity,price,fee,equity,maintenance")
        assertThat(lines).hasSize(result.liquidations.size + 1)
        assertThat(
            Files.readString(report.resolve("result.json")),
        ).contains("\"liquidationsCsv\": \"liquidations.csv\"")
        assertThat(Files.readString(report.resolve("manifest.json"))).contains("\"liquidations.csv\"")
    }
}
