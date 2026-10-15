package com.qkt.reference

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.BinaryBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * qkt against an independent engine's published example: backtesting.py 0.6.6's README strategy, SmaCross(10, 20)
 * always in the market (a cross closes the position and opens the other way), 50 shares a trade, on the GOOG daily
 * bars that ship with backtesting.py. Its trade list (`btpy-smacross-goog-fixed50-trades.csv`, from running the
 * library itself) and headline numbers must be reproduced exactly: every entry and exit date and price, every
 * trade's P&L, the final equity and the maximum drawdown. Both engines fill a bar-close signal at the next bar's
 * open, so nothing here is a tolerance.
 */
class BacktestingPySmaCrossTest {
    @TempDir
    lateinit var dir: Path

    private fun resource(name: String): List<String> =
        (javaClass.getResourceAsStream("/com/qkt/reference/$name") ?: error("missing resource $name"))
            .bufferedReader()
            .readLines()

    private fun seedBars(dataRoot: Path) {
        val store = BinaryBarStore(dataRoot)
        for (line in resource("GOOG-daily-2004-2013.csv").drop(1)) {
            val c = line.split(',')
            val day = LocalDate.parse(c[0])
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val bar =
                Candle(
                    "YAHOO:GOOG",
                    Money.of(c[1]),
                    Money.of(c[2]),
                    Money.of(c[3]),
                    Money.of(c[4]),
                    Money.of(c[5]),
                    start,
                    start + DAY_MS,
                )
            store.writeDay("YAHOO", "GOOG", TimeWindow.parse("1d"), day, listOf(bar))
        }
    }

    private fun write(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.writeString(it, text.trimIndent()) }

    /** One round trip as backtesting.py lists it: signed size, entry and exit dates and prices, P&L. */
    private data class RoundTrip(
        val size: String,
        val entry: String,
        val exit: String,
        val entryPrice: String,
        val exitPrice: String,
        val pnl: String,
    )

    private fun n(value: String): String = BigDecimal(value).stripTrailingZeros().toPlainString()

    private fun date(ms: String): String =
        Instant
            .ofEpochMilli(ms.toLong())
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toString()

    private fun qktRoundTrips(trades: Path): List<RoundTrip> {
        val lines = Files.readAllLines(trades)
        val header = lines.first().split(',')
        val rows = lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }
        val opens = rows.filter { it["reducedExposure"] == "false" }.associateBy { it["legId"] }
        return rows.filter { it["reducedExposure"] == "true" }.map { close ->
            val open = opens[close["legId"]] ?: error("no opening fill for ${close["legId"]}")
            val qty = n(open.getValue("quantity"))
            RoundTrip(
                if (open["side"] == "BUY") qty else "-$qty",
                date(open.getValue("timestamp")),
                date(close.getValue("timestamp")),
                n(open.getValue("price")),
                n(close.getValue("price")),
                n(BigDecimal(close["realized"]).setScale(2, RoundingMode.HALF_EVEN).toPlainString()),
            )
        }
    }

    private fun referenceRoundTrips(): List<RoundTrip> =
        resource("btpy-smacross-goog-fixed50-trades.csv").drop(1).map { line ->
            val c = line.split(',')
            RoundTrip(n(c[0]), c[1], c[2], n(c[3]), n(c[4]), n(c[5]))
        }

    @Test
    fun `qkt reproduces backtesting py's SmaCross on GOOG trade for trade`() {
        val dataRoot = dir.resolve("data")
        seedBars(dataRoot)
        write("instruments.yaml", INSTRUMENTS)
        write("config.yaml", CONFIG)
        val strategy = write("smacross.qkt", STRATEGY)
        val report = dir.resolve("report")
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(ByteArrayOutputStream()))
                val args =
                    arrayOf(
                        "backtest",
                        strategy.toString(),
                        "--from",
                        "2004-08-19",
                        "--to",
                        "2013-03-02",
                        "--data-root",
                        dataRoot.toString(),
                        "--no-fetch",
                        "--allow-incomplete",
                        "--bars",
                        "--bar-tf",
                        "1d",
                        "--config",
                        dir.resolve("config.yaml").toString(),
                        "--instruments",
                        dir.resolve("instruments.yaml").toString(),
                        "--report-dir",
                        report.toString(),
                    )
                BacktestCommand(Args(args)).run()
            } finally {
                System.setOut(original)
            }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val reference = referenceRoundTrips()
        assertThat(qktRoundTrips(report.resolve("trades.csv")).take(reference.size)).isEqualTo(reference)
        val result = Files.readString(report.resolve("result.json"))
        assertThat(result).contains("\"totalPnL\": \"62918.50000000\"")
        // backtesting.py: Max. Drawdown -0.9732% (0.973195% unrounded), from the same closes.
        assertThat(result).contains("\"maxDrawdown\": \"0.00973195\"")
    }

    private companion object {
        const val DAY_MS = 86_400_000L

        const val STRATEGY = """
            STRATEGY smacross VERSION 1
            SYMBOLS
                g = YAHOO:GOOG EVERY 1d
            LET ma1 = sma(g.close, 10), ma2 = sma(g.close, 20)
            RULES
                WHEN ma1 CROSSES ABOVE ma2
                THEN CLOSE g ; BUY g SIZING 50
                WHEN ma1 CROSSES BELOW ma2
                THEN CLOSE g ; SELL g SIZING 50
        """

        const val CONFIG = """
            starting_balance: 1000000
            risk:
              max_daily_loss: "0"
              max_order_qty: "100000000"
              max_order_notional: "100000000000"
        """

        const val INSTRUMENTS = """
            instruments:
              - qktSymbol: YAHOO:GOOG
                contractSize: 1
                volumeStep: 1
                volumeMin: 1
                pointSize: 0.01
                digits: 2
                tradeStopsLevelPoints: 0
        """
    }
}
