package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.candleToTicks
import com.qkt.marketdata.store.BinaryBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarsReplayRollupTest : BarsReplayFixture() {
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
    fun `bars replay flushes a completed final bar at the requested boundary`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val day = LocalDate.parse("2026-08-10")
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val bars =
            (0 until 6).map { index ->
                val price = Money.of("1.${15540 + index}")
                Candle(
                    symbol = "BACKTEST:EURUSD",
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = Money.of("1"),
                    startTime = start + index * 60_000L,
                    endTime = start + (index + 1) * 60_000L,
                )
            }
        BinaryBarStore(dataRoot).writeDay("BACKTEST", "EURUSD", TimeWindow.ONE_MINUTE, day, bars)
        val strategy = dir.resolve("final-bar.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY final_bar VERSION 1
            SYMBOLS
                eur = BACKTEST:EURUSD EVERY 1m WARMUP 5 BARS
            RULES
                WHEN eur.close > 0 AND POSITION.eur = 0 AND TRADES.today = 0
                THEN BUY eur SIZING 0.01
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                BacktestCommand(
                    Args(
                        arrayOf(
                            "backtest",
                            strategy.toString(),
                            "--from",
                            Instant.ofEpochMilli(start + 5 * 60_000L).toString(),
                            "--to",
                            Instant.ofEpochMilli(start + 6 * 60_000L).toString(),
                            "--data-root",
                            dataRoot.toString(),
                            "--no-fetch",
                            "--allow-incomplete",
                            "--bars",
                            "--bar-tf",
                            "1m",
                            "--json",
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(original)
            }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out.toString()).contains("\"trades\":1")
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
}
