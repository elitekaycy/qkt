package com.qkt.cli

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A bars-only symbol replays the finest stored bars, not bars of the strategy's own timeframe: with 1m bars stored
 * and a 15m strategy, a stop fills in the minute the price reached it. Replaying invented 15m ticks filled it a
 * quarter of the way into the 15m bar, before the price got there (a bot session's market buy filled at the 15m low,
 * 2026-10-20).
 */
class BarsOnlyFinestReplayTest {
    @Test
    fun `a stop on a 15m strategy fills in the stored minute that reached it`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val day = LocalDate.parse("2026-09-01")
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val minute = 60_000L
        val drop = 25 // 00:25, the tenth minute of the second 15m bar
        val bars =
            (0 until 1440).map { i ->
                val low = if (i == drop) Money.of("90") else Money.of("100")
                Candle(
                    "BINANCE:BTCUSDT",
                    Money.of("100"),
                    Money.of("100"),
                    low,
                    Money.of("100"),
                    Money.of("1"),
                    start + i * minute,
                    start + (i + 1) * minute,
                )
            }
        LocalBarStore(dataRoot).writeDay("BINANCE", "BTCUSDT", "1m", day, bars)
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                btc = BINANCE:BTCUSDT EVERY 15m
            RULES
                WHEN btc.close > 0 AND POSITION.btc = 0 AND TRADES.today = 0
                THEN BUY btc SIZING 0.1 BRACKET { STOP_LOSS BY 5, TAKE_PROFIT BY 50 }
            """.trimIndent(),
        )
        val report = dir.resolve("out")
        val original = System.out
        val originalErr = System.err
        val errBuf = ByteArrayOutputStream()
        val code =
            try {
                System.setOut(PrintStream(ByteArrayOutputStream()))
                System.setErr(PrintStream(errBuf))
                BacktestCommand(
                    Args(
                        arrayOf(
                            "backtest",
                            strategy.toString(),
                            "--from",
                            "2026-09-01T00:00:00Z",
                            "--to",
                            "2026-09-01T01:00:00Z",
                            "--data-root",
                            dataRoot.toString(),
                            "--no-fetch",
                            "--allow-incomplete",
                            "--report-dir",
                            report.toString(),
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(original)
                System.setErr(originalErr)
            }

        assertThat(code).withFailMessage(errBuf.toString()).isEqualTo(ExitCodes.SUCCESS)
        val stop = Files.readAllLines(report.resolve("trades.csv")).drop(1).single { ",Stop," in it }
        val at = stop.substringBefore(',').toLong()
        assertThat(at).isBetween(start + drop * minute, start + (drop + 1) * minute - 1)
    }
}
