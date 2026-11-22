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
 * A slot universe over the venue's event families (`KALSHI:*@front.p70 WHERE family = KXS ...`) whose one family is
 * the hourly `KALSHI:KXS` of [EventSlotData]: its member, `KXS@front.p70`, follows and trades exactly like the slot
 * declared by hand in [EventSlotBacktestTest] (the same fills and settlements, net 9.99).
 */
class SlotUniverseBacktestTest {
    private fun strategy(
        slot: String,
        loop: String = "FOR EACH m IN s DO",
        m: String = "m",
    ): String =
        """
        STRATEGY slots VERSION 1
        SYMBOLS
            $slot
            tail = KALSHI:KXS_H3_T4 EVERY 1m
        RULES
            $loop
              WHEN POSITION.$m = 0 AND $m.ask < 0.80 THEN BUY $m SIZING 10
            $loop
              WHEN $m.contract = "KXS_H2_T2" AND POSITION.$m > 0 THEN SELL $m SIZING 14
            WHEN tail.close > 2
            THEN LOG "never"
        """.trimIndent()

    private fun backtest(
        dir: Path,
        source: String,
    ): Pair<Int, List<String>> {
        val data = dir.resolve("data").also { EventSlotData.seed(it) }
        val file = dir.resolve("s.qkt").also { Files.writeString(it, source) }
        val out = ByteArrayOutputStream()
        val (o, e) = System.out to System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(out))
                val argv =
                    arrayOf("backtest", file.toString(), "--from", "2024-03-04", "--to", "2024-03-05") +
                        arrayOf("--data-root", data.toString(), "--no-fetch", "--allow-incomplete", "--json") +
                        arrayOf("--report-dir", dir.resolve("report").toString())
                BacktestCommand(Args(argv)).run()
            } finally {
                System.setOut(o)
                System.setErr(e)
            }
        assertThat(code).describedAs(out.toString()).isEqualTo(ExitCodes.SUCCESS)
        val fills = Files.readAllLines(dir.resolve("report/trades.csv")).drop(1)
        val settled = Files.readAllLines(dir.resolve("report/settlements.csv")).drop(1)
        // Timestamp through realized P&L: order and leg ids differ by design (a member mints its own).
        return code to (fills + settled).map { it.split(',').take(9).joinToString(",") }
    }

    @Test
    fun `a slot universe's member trades exactly like the same slot declared by hand`(
        @TempDir dir: Path,
    ) {
        val declared = backtest(dir.resolve("declared"), strategy("p70 = KALSHI:KXS@front.p70 EVERY 1m", "", "p70"))
        val universe =
            backtest(
                dir.resolve("universe"),
                strategy("s = KALSHI:*@front.p70 WHERE family = KXS TOP 3 BY family EVERY 1m"),
            )

        assertThat(declared.second).hasSizeGreaterThan(5)
        assertThat(universe.second).containsExactlyElementsOf(declared.second)
    }
}
