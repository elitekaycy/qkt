package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import com.qkt.common.Side
import com.qkt.marketdata.Candle
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.Print
import com.qkt.marketdata.flow.TapeStore
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

/**
 * `qkt backtest` replays the stored tape and liquidations, a bar's flow read one bar after it closes, and refuses a
 * run whose tape is missing, from the day its first reads reach back to.
 */
class TradeFlowBacktestTest {
    private val perp = "BINANCE_UM:BTCUSDT"
    private val quarter = 900_000L
    private val days = listOf("2024-09-01", "2024-09-02", "2024-09-03")

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun print(
        iso: String,
        size: String,
        side: Side,
    ) = Print(iso, at(iso), BigDecimal("57000"), BigDecimal(size), side)

    private fun seed(
        dir: Path,
        tape: Boolean,
    ): Path {
        val data = dir.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("instruments.yaml"),
            "futures:\n  - { root: $perp, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, volumeMin: 0.001, perpetual: BTCUSDT }\n",
        )
        val bars = LocalBarStore(data)
        val store = TapeStore(data)
        for (d in days) {
            val dayStart = at("${d}T00:00:00Z")
            val px = BigDecimal("57000")
            val candles =
                (0 until 96).map {
                    Candle(perp, px, px, px, px, BigDecimal.ONE, dayStart + it * quarter, dayStart + (it + 1) * quarter)
                }
            bars.writeDay("BINANCE_UM", "BTCUSDT", "15m", LocalDate.parse(d), candles)
            bars.recordDay("BINANCE_UM", "BTCUSDT", "15m", LocalDate.parse(d))
            if (tape) {
                store.write(perp, FlowKind.TRADES, LocalDate.parse(d), emptyList())
                store.write(perp, FlowKind.LIQUIDATIONS, LocalDate.parse(d), emptyList())
            }
        }
        if (tape) {
            // Balanced flow, then aggressive buying in the 03:30 bar; longs liquidated in the 05:00 bar.
            store.write(
                perp,
                FlowKind.TRADES,
                LocalDate.parse("2024-09-02"),
                listOf(
                    print("2024-09-02T01:00:00Z", "1", Side.BUY),
                    print("2024-09-02T01:00:01Z", "1", Side.SELL),
                    print("2024-09-02T03:31:00Z", "3", Side.BUY),
                    print("2024-09-02T03:44:59.999Z", "0.5", Side.SELL),
                ),
            )
            store.write(
                perp,
                FlowKind.LIQUIDATIONS,
                LocalDate.parse("2024-09-02"),
                listOf(print("2024-09-02T05:10:00Z", "0.8", Side.SELL)),
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
            "STRATEGY flow VERSION 1\nSYMBOLS\n    btc = $perp EVERY 15m\nRULES\n" +
                "    WHEN btc.buy_volume[1] > btc.sell_volume[1] + 1 AND POSITION.btc = 0\n" +
                "    THEN BUY btc SIZING 0.01\n" +
                "    WHEN btc.long_liq_volume[1] > 0.5 AND POSITION.btc > 0\n    THEN SELL btc SIZING 0.01\n",
        )
        val out = ByteArrayOutputStream()
        val (o, e) = System.out to System.err
        val args =
            arrayOf(
                "backtest",
                "$strategy",
                "--from",
                "2024-09-02",
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
    fun `a bar's flow is acted on at the close of the bar after it, never at its own close`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, tape = true))

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        val trades = Files.readAllLines(dir.resolve("report/trades.csv"))
        assertThat(trades).describedAs(output).hasSize(3)
        assertThat(trades[1])
            .describedAs("bought on the 03:30 bar's flow, at the 04:00 close")
            .startsWith("${at("2024-09-02T04:00:00Z")},flow,$perp,BUY")
        assertThat(trades[2])
            .describedAs("sold on the 05:00 bar's liquidations, at the 05:30 close")
            .startsWith("${at("2024-09-02T05:30:00Z")},flow,$perp,SELL")
    }

    @Test
    fun `a run whose tape is not stored is refused naming the fetch from the day its reads reach back to`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, tape = false))

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("qkt fetch $perp --tape --from 2024-09-01 --to 2024-09-03")
    }
}
