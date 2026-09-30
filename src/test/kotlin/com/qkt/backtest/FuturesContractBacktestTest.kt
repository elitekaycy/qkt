package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.ListedContract
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A dated futures contract backtested through the full `qkt backtest` command: proves the
 * instruments file's `futures:` roots and the contract catalog reach the replay's registry, fee
 * model and start-up guards.
 */
class FuturesContractBacktestTest {
    private val contract = "BTCUSDT_240927"

    private fun seed(dir: Path): Path {
        val data = dir.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("instruments.yaml"),
            "futures:\n  - { root: BINANCE_UM:BTCUSDT, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, volumeMin: 0.001, takerFeeRate: 0.0005 }\n",
        )
        ContractCatalogStore(data).write(
            ContractCatalog(
                "BINANCE_UM:BTCUSDT",
                listOf(ListedContract(contract, Instant.parse("2024-09-27T08:00:00Z").toEpochMilli())),
            ),
        )
        val store = LocalBarStore(data)
        for (d in listOf("2024-09-02", "2024-09-03")) {
            val step = 15 * 60_000L
            val day = LocalDate.parse(d)
            val start = Instant.parse("${d}T00:00:00Z").toEpochMilli()
            val bars =
                (0 until 96).map { m ->
                    val px = BigDecimal("%.1f".format(57_000.0 + 400.0 * sin((m + day.dayOfMonth * 96) / 3.0)))
                    Candle(
                        "BINANCE_UM:$contract",
                        px,
                        px.add(BigDecimal("5")),
                        px.subtract(BigDecimal("5")),
                        px,
                        BigDecimal.ONE,
                        start + m * 60_000L,
                        start + (m + 1) * 60_000L,
                    )
                }
            store.writeDay("BINANCE_UM", contract, "15m", day, bars)
            store.recordDay("BINANCE_UM", contract, "15m", day)
        }
        return data
    }

    private fun backtest(
        dir: Path,
        data: Path,
        symbol: String,
    ): Pair<Int, String> {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY fut VERSION 1
            SYMBOLS
                btc = $symbol EVERY 15m
            RULES
                WHEN ema(btc.close, 3) CROSSES ABOVE ema(btc.close, 9)
                THEN BUY btc SIZING 0.01
                WHEN ema(btc.close, 3) CROSSES BELOW ema(btc.close, 9)
                THEN CLOSE btc
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val (o, e) = System.out to System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(err))
                BacktestCommand(
                    Args(
                        arrayOf(
                            "backtest",
                            strategy.toString(),
                            "--from",
                            "2024-09-02",
                            "--to",
                            "2024-09-04",
                            "--data-root",
                            data.toString(),
                            "--no-fetch",
                            "--allow-incomplete",
                            "--json",
                            "--report-dir",
                            dir.resolve("report").toString(),
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(o)
                System.setErr(e)
            }
        return code to (out.toString() + err.toString())
    }

    @Test
    fun `a catalogued contract backtests and pays notional taker fees`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir), "BINANCE_UM:$contract")
        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        val fills = Files.readAllLines(dir.resolve("report/trades.csv")).drop(1).map { it.split(',') }
        assertThat(fills).isNotEmpty
        val expected =
            fills.fold(
                BigDecimal.ZERO,
            ) { acc, f -> acc.add(BigDecimal(f[6]).multiply(BigDecimal(f[7])).multiply(BigDecimal("0.0005"))) }
        val paid = Regex("\"commissionPaid\":(-?[0-9.]+)").find(output)?.groupValues?.get(1)
        assertThat(BigDecimal(paid)).isEqualByComparingTo(expected)
    }

    @Test
    fun `a contract missing from the catalog fails the run before trading`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir), "BINANCE_UM:BTCUSDT_241227")
        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("BTCUSDT_241227").contains("--catalog")
    }
}
