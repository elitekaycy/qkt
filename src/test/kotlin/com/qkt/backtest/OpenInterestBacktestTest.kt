package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.Candle
import com.qkt.marketdata.openinterest.OpenInterest
import com.qkt.marketdata.openinterest.OpenInterestStore
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

/** A strategy reading `<alias>.open_interest` in `qkt backtest` sees each stored figure from the instant it was known. */
class OpenInterestBacktestTest {
    private val perp = "BINANCE_UM:BTCUSDT"
    private val start = Instant.parse("2024-09-02T00:00:00Z").toEpochMilli()
    private val minute = 60_000L

    /** Known at 06:07:30, between two 15-minute closes: the first figure above 1000. */
    private val rise = start + 6 * 60 * minute + 7 * minute + 30_000

    private fun seed(
        dir: Path,
        figures: List<OpenInterest>?,
    ): Path {
        val data = dir.resolve("data")
        Files.createDirectories(data)
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
        figures?.let { OpenInterestStore(data).merge(perp, it) }
        return data
    }

    /** Every five minutes over both days: 500 contracts, and 1500 from [rise] on. */
    private fun figures(): List<OpenInterest> =
        (0 until 2 * 288).map { start + it * 5 * minute }.map { t -> OpenInterest(t, BigDecimal("500")) } +
            OpenInterest(rise, BigDecimal("1500")) +
            (0 until 2 * 288).map { start + it * 5 * minute }.filter { it > rise }.map {
                OpenInterest(
                    it,
                    BigDecimal("1500"),
                )
            }

    private fun backtest(
        dir: Path,
        data: Path,
    ): Pair<Int, String> {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            "STRATEGY oi VERSION 1\nSYMBOLS\n    btc = $perp EVERY 15m\nRULES\n" +
                "    WHEN btc.open_interest > 1000 AND POSITION.btc = 0 AND TRADES.today = 0\n" +
                "    THEN BUY btc SIZING 0.01\n",
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
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(o)
                System.setErr(e)
            }
        return code to out.toString()
    }

    @Test
    fun `a rule on open interest fires only once the figure that crosses is known, never on an earlier close`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, figures()))

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        val fills = Files.readAllLines(dir.resolve("report/trades.csv")).drop(1)
        assertThat(fills).describedAs(output).hasSize(1)
        val filledAt = fills.single().substringBefore(',').toLong()
        assertThat(filledAt).isGreaterThanOrEqualTo(rise).isLessThanOrEqualTo(rise + 15 * minute)
    }

    @Test
    fun `a backtest reading open interest it has not stored is refused naming the fetch`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, null))

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output)
            .contains("reads the open interest of $perp")
            .contains("qkt fetch $perp --open-interest --from 2024-09-02 --to 2024-09-04")
    }

    @Test
    fun `stored open interest that stops short of the run's end is refused as a gap`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, figures().filter { it.timeMs < start + 86_400_000L }))

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("stored open interest that ends 2024-09-02")
    }
}
