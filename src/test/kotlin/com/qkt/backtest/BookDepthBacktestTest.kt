package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.Candle
import com.qkt.marketdata.depth.BookDepth
import com.qkt.marketdata.depth.BookDepthStore
import com.qkt.marketdata.depth.BookLevel
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

/** A strategy reading `<alias>.book_imbalance` in `qkt backtest` sees each stored snapshot from the instant it was stamped. */
class BookDepthBacktestTest {
    private val perp = "BINANCE_UM:BTCUSDT"
    private val start = Instant.parse("2024-09-02T00:00:00Z").toEpochMilli()
    private val minute = 60_000L

    /** Stamped at 06:07:30, between two 15-minute closes: the first book leaning to the bid. */
    private val lean = start + 6 * 60 * minute + 7 * minute + 30_000

    private fun seed(
        dir: Path,
        snapshots: List<BookDepth>?,
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
        snapshots?.let { BookDepthStore(data).merge(perp, it) }
        return data
    }

    private fun book(
        timeMs: Long,
        bid: String,
    ) = BookDepth(
        timeMs,
        listOf(BookLevel(BigDecimal("57000"), BigDecimal(bid))),
        listOf(BookLevel(BigDecimal("57000.5"), BigDecimal("1"))),
    )

    /** A book every minute over both days: balanced, and three to one on the bid (imbalance 0.5) from [lean] on. */
    private fun snapshots(): List<BookDepth> =
        (0 until 2 * 1_440).map { start + it * minute }.map { t -> book(t, if (t > lean) "3" else "1") } +
            book(lean, "3")

    private fun backtest(
        dir: Path,
        data: Path,
    ): Pair<Int, String> {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            "STRATEGY depth VERSION 1\nSYMBOLS\n    btc = $perp EVERY 15m\nRULES\n" +
                "    WHEN btc.book_imbalance > 0.4 AND btc.ask_depth > 0 AND POSITION.btc = 0 AND TRADES.today = 0\n" +
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
    fun `a rule on book imbalance fires only once the leaning book is known, never on an earlier close`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, snapshots()))

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        val fills = Files.readAllLines(dir.resolve("report/trades.csv")).drop(1)
        assertThat(fills).describedAs(output).hasSize(1)
        val filledAt = fills.single().substringBefore(',').toLong()
        assertThat(filledAt).isGreaterThanOrEqualTo(lean).isLessThanOrEqualTo(lean + 15 * minute)
    }

    @Test
    fun `a backtest reading depth it has not stored is refused naming the fetch`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, null))

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output)
            .contains("reads the order-book depth of $perp")
            .contains("qkt fetch $perp --depth --from 2024-09-02 --to 2024-09-04")
    }

    @Test
    fun `stored depth with an hour missing inside the run is refused as a gap`(
        @TempDir dir: Path,
    ) {
        val hole = start + 30 * 60 * minute
        val (code, output) = backtest(dir, seed(dir, snapshots().filter { it.timeMs !in hole..hole + 60 * minute }))

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("stored depth that skips 2024-09-03T05:59:00Z to 2024-09-03T07:01:00Z")
    }
}
