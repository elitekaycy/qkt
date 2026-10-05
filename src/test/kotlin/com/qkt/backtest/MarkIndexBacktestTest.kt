package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.Candle
import com.qkt.marketdata.marks.MarkSample
import com.qkt.marketdata.marks.MarkStore
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

/** `qkt backtest` replays stored marks, each seen only after its time, and refuses a run whose marks are missing. */
class MarkIndexBacktestTest {
    private val perp = "BINANCE_UM:BTCUSDT"
    private val quarter = 900_000L
    private val days = listOf("2024-09-02", "2024-09-03")

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun seed(
        dir: Path,
        marks: Boolean,
    ): Path {
        val data = dir.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("instruments.yaml"),
            "futures:\n  - { root: $perp, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, volumeMin: 0.001, perpetual: BTCUSDT }\n",
        )
        val bars = LocalBarStore(data)
        val store = MarkStore(data)
        for (d in days) {
            val dayStart = at("${d}T00:00:00Z")
            val px = BigDecimal("57000")
            val candles =
                (0 until 96).map {
                    Candle(
                        perp,
                        px,
                        px,
                        px,
                        px,
                        BigDecimal.ONE,
                        dayStart + it * quarter,
                        dayStart + (it + 1) * quarter,
                    )
                }
            bars.writeDay("BINANCE_UM", "BTCUSDT", "15m", LocalDate.parse(d), candles)
            bars.recordDay("BINANCE_UM", "BTCUSDT", "15m", LocalDate.parse(d))
            if (marks) store.write(perp, quarter, LocalDate.parse(d), emptyList())
        }
        if (marks) {
            // Flat until a premium of 10 is reported exactly at 03:30, a bar's close.
            store.write(
                perp,
                quarter,
                LocalDate.parse("2024-09-02"),
                listOf(
                    MarkSample(at("2024-09-02T00:10:00Z"), BigDecimal("57000"), BigDecimal("57000")),
                    MarkSample(at("2024-09-02T03:30:00Z"), BigDecimal("57010"), BigDecimal("57000")),
                ),
            )
        }
        return data
    }

    private fun backtest(
        dir: Path,
        data: Path,
    ): Pair<Int, String> {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            "STRATEGY premium VERSION 1\nSYMBOLS\n    btc = $perp EVERY 15m\nRULES\n" +
                "    WHEN btc.mark - btc.index > 5 AND POSITION.btc = 0\n    THEN BUY btc SIZING 0.01\n",
        )
        val out = ByteArrayOutputStream()
        val (o, e) = System.out to System.err
        val args =
            arrayOf(
                "backtest",
                "$strategy",
                "--from",
                days.first(),
                "--to",
                "2024-09-04",
                "--data-root",
                "$data",
                "--no-fetch",
                "--allow-incomplete",
                "--position-mode",
                "netting",
                "--funding",
                "off",
                "--json",
                "--report-dir",
                "${dir.resolve("report")}",
            )
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(out))
                BacktestCommand(Args(args)).run()
            } finally {
                System.setOut(o)
                System.setErr(e)
            }
        return code to out.toString()
    }

    @Test
    fun `a stored mark is acted on at the first bar close after its time, never at its own instant`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, marks = true))

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        val trades = Files.readAllLines(dir.resolve("report/trades.csv"))
        assertThat(trades).describedAs(output).hasSize(2)
        assertThat(trades[1]).contains("${at("2024-09-02T03:45:00Z")}")
    }

    @Test
    fun `a run whose marks are not stored is refused naming the fetch`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, marks = false))

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("qkt fetch $perp --marks --tf 15m --from 2024-09-02 --to 2024-09-03")
    }
}
