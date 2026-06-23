package com.qkt.cli

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.candleToTicks
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarsReplayTest {
    private fun aggregate(
        ticks: List<Tick>,
        tf: String,
    ): List<Candle> {
        val out = ArrayList<Candle>()
        val agg = CandleAggregator.standalone(TimeWindow.parse(tf)) { out.add(it) }
        ticks.forEach { agg.onTick(it) }
        agg.flushClosed(Long.MAX_VALUE)
        return out
    }

    private fun ticksFor(days: Int): List<Tick> {
        val start =
            LocalDate
                .parse("2024-01-02")
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        return (0 until days * 1440).map { m ->
            val mid = 1850.0 + 8.0 * sin(m / 40.0)
            Tick("XAUUSD", Money.of("%.3f".format(mid)), start + m * 60_000L)
        }
    }

    private fun seedTicks(
        dataRoot: Path,
        days: Int,
    ) {
        ticksFor(days)
            .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneOffset.UTC) }
            .forEach { (day, dayTicks) ->
                val f = dataRoot.resolve("symbols").resolve("XAUUSD").resolve("$day.bin")
                Files.createDirectories(f.parent)
                BinaryTickWriter().write(f, "XAUUSD", dayTicks)
            }
    }

    private fun strategyFile(dir: Path): Path {
        val s = dir.resolve("s.qkt")
        Files.writeString(
            s,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 15m
            RULES
                WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1 BRACKET { STOP LOSS PCT 0.01, TAKE PROFIT RR 2 }
                WHEN ema(gold.close, 3) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        return s
    }

    @Test
    fun `multi-timeframe rollup from 15m bars equals direct 1h`() {
        val ticks = ticksFor(1)
        val direct1h = aggregate(ticks, "1h")
        val rolled1h = aggregate(aggregate(ticks, "15m").flatMap { candleToTicks(it) }, "1h")
        assertThat(rolled1h.size).isEqualTo(direct1h.size)
        for (i in direct1h.indices) {
            assertThat(rolled1h[i].open).isEqualByComparingTo(direct1h[i].open)
            assertThat(rolled1h[i].high).isEqualByComparingTo(direct1h[i].high)
            assertThat(rolled1h[i].low).isEqualByComparingTo(direct1h[i].low)
            assertThat(rolled1h[i].close).isEqualByComparingTo(direct1h[i].close)
        }
    }

    @Test
    fun `backtest --bars replays built bars and trades`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 3)
        val build =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        "XAUUSD",
                        "--tf",
                        "15m",
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-05",
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(build).isEqualTo(ExitCodes.SUCCESS)

        val out = ByteArrayOutputStream()
        val orig = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                BacktestCommand(
                    Args(
                        arrayOf(
                            "backtest",
                            strategyFile(dir).toString(),
                            "--from",
                            "2024-01-02",
                            "--to",
                            "2024-01-05",
                            "--data-root",
                            dataRoot.toString(),
                            "--no-fetch",
                            "--allow-incomplete",
                            "--bars",
                            "--json",
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(orig)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out.toString()).contains("\"trades\":")
    }

    @Test
    fun `a 15m strategy replays off 1m bars identically to direct 15m bars`(
        @TempDir dir: Path,
    ) {
        val from = "2024-01-02"
        val to = "2024-01-05"

        fun build(
            dataRoot: Path,
            tf: String,
        ) {
            seedTicks(dataRoot, days = 3)
            val code =
                DataCommand(
                    Args(
                        arrayOf(
                            "data",
                            "build-bars",
                            "XAUUSD",
                            "--tf",
                            tf,
                            "--from",
                            from,
                            "--to",
                            to,
                            "--data-root",
                            dataRoot.toString(),
                        ),
                    ),
                ).run()
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        }

        fun runBars(dataRoot: Path): String {
            val out = ByteArrayOutputStream()
            val orig = System.out
            val code =
                try {
                    System.setOut(PrintStream(out))
                    BacktestCommand(
                        Args(
                            arrayOf(
                                "backtest",
                                strategyFile(dir).toString(),
                                "--from",
                                from,
                                "--to",
                                to,
                                "--data-root",
                                dataRoot.toString(),
                                "--no-fetch",
                                "--allow-incomplete",
                                "--bars",
                                "--json",
                            ),
                        ),
                    ).run()
                } finally {
                    System.setOut(orig)
                }
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            return out.toString()
        }

        fun field(
            json: String,
            key: String,
        ): String = Regex("\"$key\":\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1) ?: error("no $key in $json")

        val rootFine = dir.resolve("fine")
        val rootDirect = dir.resolve("direct")
        // Only 1m built: resolver must feed 1m and let CandleHub aggregate up to the strategy's 15m.
        build(rootFine, "1m")
        // 15m built: resolver feeds 15m directly (the trivial case).
        build(rootDirect, "15m")
        val jsonFine = runBars(rootFine)
        val jsonDirect = runBars(rootDirect)
        // On-the-fly aggregation from 1m must reproduce the direct-15m run exactly.
        assertThat(field(jsonFine, "trades")).isEqualTo(field(jsonDirect, "trades"))
        assertThat(field(jsonFine, "totalPnL")).isEqualTo(field(jsonDirect, "totalPnL"))
    }

    @Test
    fun `backtest --bars ignores tick-store holes without --allow-incomplete`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 3)
        DataCommand(
            Args(
                arrayOf(
                    "data",
                    "build-bars",
                    "XAUUSD",
                    "--tf",
                    "15m",
                    "--from",
                    "2024-01-02",
                    "--to",
                    "2024-01-05",
                    "--data-root",
                    dataRoot.toString(),
                ),
            ),
        ).run()
        // Punch a hole in the TICK store after the bars are built: a --bars run must not care.
        Files.delete(dataRoot.resolve("symbols").resolve("XAUUSD").resolve("2024-01-03.bin"))
        // No --allow-incomplete: the tick hole would throw IncompleteDataException if --bars still
        // validated the tick store. It must not, because --bars only reads the bar store.
        val code =
            BacktestCommand(
                Args(
                    arrayOf(
                        "backtest",
                        strategyFile(dir).toString(),
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-05",
                        "--data-root",
                        dataRoot.toString(),
                        "--no-fetch",
                        "--bars",
                        "--json",
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `backtest --bars errors when bars are not built`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 1)
        val code =
            BacktestCommand(
                Args(
                    arrayOf(
                        "backtest",
                        strategyFile(dir).toString(),
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-03",
                        "--data-root",
                        dataRoot.toString(),
                        "--no-fetch",
                        "--allow-incomplete",
                        "--bars",
                        "--json",
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }
}
