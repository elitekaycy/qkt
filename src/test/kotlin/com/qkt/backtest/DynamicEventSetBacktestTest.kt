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
 * A `DYNAMIC` set over the event catalog of the hourly family `KALSHI:KXS` ([EventSlotData]: events H1, H2, H3
 * expiring at 11:00, 12:00 and 13:00, every contract listed from 09:00), ranked by expiry and cut to the next two
 * events' eight contracts at each hourly close. The members roll forward event by event: H1 and H2 at 10:00, H2 and H3
 * at 11:00, H3 alone at 12:00; each member first evaluates at the close after it joined.
 *
 * - 11:00 H2's 1h bars close with asks 0.96, 0.81, 0.69, 0.21: the 300 strike (0.69) is bought.
 * - 12:00 H3's bars close with asks 0.86, 0.73, 0.51, 0.31: the 200 and 300 strikes are bought.
 * - H1 never trades: it leaves at 11:00, when it expires, before its first evaluation.
 */
class DynamicEventSetBacktestTest {
    @Test
    fun `a DYNAMIC set rolls through an event catalog's contracts as they list and expire`(
        @TempDir dir: Path,
    ) {
        val data = dir.resolve("data").also { EventSlotData.seed(it) }
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY hourly VERSION 1
            SYMBOLS
                s = KALSHI:* WHERE family = KXS TOP 8 BY expiry_at ASC DYNAMIC EVERY 1h
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
        assertThat(buys).containsExactlyInAnyOrder("KALSHI:KXS_H2_T3", "KALSHI:KXS_H3_T2", "KALSHI:KXS_H3_T3")
    }
}
