package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * An event ladder of the hourly family `KALSHI:KXS` ([EventSlotData]: events H1, H2, H3 closing at 11:00, 12:00 and
 * 13:00), `KALSHI:KXS@front.* ... EVERY 1m`: its members are the four contracts of whichever event closes first, so
 * they roll H1, then H2 at 11:00, then H3 at 12:00, each first evaluating at the minute after it joined.
 *
 * - 10:01 H1's asks are 0.91, 0.71, 0.41, 0.11: the 200 strike (0.71) is bought.
 * - 11:01 H2's asks are 0.96, 0.81, 0.69, 0.21: the 300 strike (0.69) is bought.
 * - 12:01 H3's asks are 0.86, 0.73, 0.51, 0.31: the 200 and 300 strikes are bought.
 */
class EventLadderBacktestTest {
    @Test
    fun `a front ladder trades the contracts of each event in turn as the one before it closes`(
        @TempDir dir: Path,
    ) {
        val data = dir.resolve("data").also { EventSlotData.seed(it) }
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY hourly VERSION 1
            SYMBOLS
                s = KALSHI:KXS@front.* WHERE kind = event TOP 4 BY symbol ASC EVERY 1m
            RULES
                FOR EACH m IN s DO
                  WHEN POSITION.m = 0 AND m.ask < 0.75 AND m.ask > 0.5 THEN BUY m SIZING 10
            """.trimIndent(),
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
                            "2024-03-04",
                            "--to",
                            "2024-03-05",
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

        assertThat(code).describedAs(out.toString()).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out.toString()).contains("symbol set 's': DYNAMIC over 12 candidate(s): KXS_H1_T1")
        val fills = Files.readAllLines(dir.resolve("report/trades.csv")).drop(1).map { it.split(',') }
        val buys = fills.filter { it[3] == "BUY" }.map { it[2] }
        assertThat(buys).containsExactlyInAnyOrder(
            "KALSHI:KXS_H1_T2",
            "KALSHI:KXS_H2_T3",
            "KALSHI:KXS_H3_T2",
            "KALSHI:KXS_H3_T3",
        )
    }
}
