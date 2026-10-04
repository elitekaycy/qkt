package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import com.qkt.instrument.FundingRate
import com.qkt.instrument.FundingRateStore
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A perpetual held through `qkt backtest` pays the stored funding rates, and one without them is refused. */
class PerpetualFundingBacktestTest {
    private val perp = "BINANCE_UM:BTCUSDT"
    private val hour = 3_600_000L
    private val start = Instant.parse("2024-09-02T00:00:00Z").toEpochMilli()

    private fun seed(
        dir: Path,
        rates: List<FundingRate>?,
    ): Path {
        val data = dir.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("instruments.yaml"),
            "futures:\n  - { root: $perp, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, volumeMin: 0.001, perpetual: BTCUSDT }\n",
        )
        val store = LocalBarStore(data)
        for (d in listOf("2024-09-02", "2024-09-03")) {
            val dayStart = Instant.parse("${d}T00:00:00Z").toEpochMilli()
            val bars =
                (0 until 96).map { m ->
                    val px = BigDecimal("57000")
                    Candle(perp, px, px, px, px, BigDecimal.ONE, dayStart + m * 900_000L, dayStart + (m + 1) * 900_000L)
                }
            store.writeDay("BINANCE_UM", "BTCUSDT", "15m", LocalDate.parse(d), bars)
            store.recordDay("BINANCE_UM", "BTCUSDT", "15m", LocalDate.parse(d))
        }
        rates?.let { FundingRateStore(data).merge(perp, it) }
        return data
    }

    private fun backtest(
        dir: Path,
        data: Path,
        side: String = "BUY",
        vararg extra: String,
    ): Pair<Int, String> {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            "STRATEGY perp VERSION 1\nSYMBOLS\n    btc = $perp EVERY 15m\nRULES\n" +
                "    WHEN btc.close > 0 AND POSITION.btc = 0 AND TRADES.today = 0\n    THEN $side btc SIZING 0.01\n",
        )
        val out = ByteArrayOutputStream()
        val (o, e) = System.out to System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(out))
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
                            "--position-mode",
                            "netting",
                            "--json",
                            "--report-dir",
                            dir.resolve("report").toString(),
                            *extra,
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(o)
                System.setErr(e)
            }
        return code to out.toString()
    }

    private fun every8h(rate: String) =
        (0..6).map {
            FundingRate(start + it * 8 * hour, BigDecimal(rate), BigDecimal("57100"))
        }

    private fun paid(output: String) =
        Regex("\"fundingPaid\":(-?[0-9.]+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.let(::BigDecimal)

    @Test
    fun `a long pays each rate after its entry at the rate's price, and its realized pnl is net of it`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, every8h("0.0001")))

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        // Entered on the first bar close (00:15): the six rates from 08:00 on fall inside the run.
        val each = BigDecimal("0.01").multiply(BigDecimal("57100")).multiply(BigDecimal("0.0001"))
        assertThat(paid(output)).isEqualByComparingTo(each.multiply(BigDecimal(6)))
        val financing = Files.readAllLines(dir.resolve("report/financing.csv"))
        assertThat(financing).contains("funding,0.34260000,-0.34260000")
    }

    @Test
    fun `a short is paid a positive rate`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, every8h("0.0001")), "SELL")

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        assertThat(paid(output)).isEqualByComparingTo("-0.3426")
    }

    @Test
    fun `a perpetual without stored rates is refused naming the fetch, unless funding is turned off`(
        @TempDir dir: Path,
    ) {
        val data = seed(dir, null)

        val (refused, why) = backtest(dir, data)
        val (code, output) = backtest(dir, data, "BUY", "--funding", "off")

        assertThat(refused).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(why).contains("$perp is a perpetual").contains("qkt fetch $perp --funding --from 2024-09-02")
        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        assertThat(paid(output)).isNull()
    }

    @Test
    fun `rates that stop short of the run's end are refused as a gap`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, every8h("0.0001").take(2)))

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("its stored rates end 2024-09-02")
    }
}
