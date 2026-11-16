package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import com.qkt.instrument.SlotFamilyFixture
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The `@front.p70` slot of the hourly family `KALSHI:KXS` (three events of four strikes, [SlotFamilyFixture]) through
 * the full `qkt backtest` command, from minute quote ticks. Mids, each quoted a cent either side:
 * H1 0.90/0.70/0.40/0.10; H2 0.95/0.80/0.68/0.20, its 300 strike jumping to 0.88 at 11:30; H3 0.85/0.72/0.50/0.30.
 *
 * - 10:02 the slot follows H1's 200 strike: BUY 10 at the 0.71 ask; it settles at 1 at 11:00 (+2.90, fee 0.15).
 * - 11:01 the slot follows H2's 300 strike: BUY 10 at 0.69 (fee 0.15).
 * - 11:30 that strike jumps, so from 11:31 the slot follows H2's 200 strike. At 11:32 `SELL 14` reduces first: it
 *   sells the 10 held of the 300 strike at its own 0.87 bid (+1.80, fee 0.08), and only the remaining 4 sell NO on the
 *   200 strike at 0.79 (fee 0.05), which settles at 0 at 12:00 (+3.16).
 * - 12:01 the slot follows H3's 200 strike: BUY 10 at 0.73 (fee 0.14); it settles at 1 at 13:00 (+2.70).
 *
 * Gross 10.56, fees 0.57, net 9.99. Every order lands on a real contract; none names the slot.
 */
class EventSlotBacktestTest {
    private fun seed(dir: Path): Path = dir.resolve("data").also { EventSlotData.seed(it) }

    private fun backtest(
        dir: Path,
        data: Path,
        extraSymbol: String = "",
        extraRule: String = "",
    ): Pair<Int, String> {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY slots VERSION 1
            SYMBOLS
                p70 = KALSHI:KXS@front.p70 EVERY 1m
                tail = KALSHI:KXS_H3_T4 EVERY 1m
            $extraSymbol
            RULES
                WHEN POSITION.p70 = 0 AND p70.ask < 0.80
                THEN BUY p70 SIZING 10
                WHEN p70.contract = "KXS_H2_T2" AND POSITION.p70 > 0
                THEN SELL p70 SIZING 14
                WHEN tail.close > 2
                THEN LOG "never"
            $extraRule
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
        return code to out.toString()
    }

    private fun number(
        output: String,
        field: String,
    ): BigDecimal = BigDecimal(requireNotNull(Regex("\"$field\":(-?[0-9.]+)").find(output)).groupValues[1])

    private fun plain(value: String): String = BigDecimal(value).stripTrailingZeros().toPlainString()

    @Test
    fun `a probability slot trades across events, reduces first and settles at one and zero`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir))

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        assertThat(number(output, "commissionPaid")).isEqualByComparingTo("0.57")
        assertThat(number(output, "finalRealized")).isEqualByComparingTo("9.99")
        assertThat(number(output, "finalUnrealized")).isEqualByComparingTo("0")
        assertThat(number(output, "totalPnL")).isEqualByComparingTo("9.99")
        val fills = Files.readAllLines(dir.resolve("report/trades.csv")).drop(1).map { it.split(',') }
        assertThat(fills.map { listOf(it[2], it[3], plain(it[6]), plain(it[7])) }).contains(
            listOf("KALSHI:KXS_H1_T2", "BUY", "10", "0.71"),
            listOf("KALSHI:KXS_H2_T3", "BUY", "10", "0.69"),
            listOf("KALSHI:KXS_H2_T3", "SELL", "10", "0.87"),
            listOf("KALSHI:KXS_H2_T2", "SELL", "4", "0.79"),
            listOf("KALSHI:KXS_H3_T2", "BUY", "10", "0.73"),
        )
        assertThat(fills.map { it[2] }).noneMatch { '@' in it }
        val settlements = Files.readAllLines(dir.resolve("report/settlements.csv")).drop(1).map { it.split(',') }
        val settled = settlements.map { listOf(it[0].toLong(), it[2], it[3], plain(it[4]), plain(it[5])) }
        assertThat(settled).containsExactly(
            listOf(SlotFamilyFixture.closeMs(1), "KALSHI:KXS_H1_T2", "SELL", "10", "1"),
            listOf(SlotFamilyFixture.closeMs(2), "KALSHI:KXS_H2_T2", "BUY", "4", "0"),
            listOf(SlotFamilyFixture.closeMs(3), "KALSHI:KXS_H3_T2", "SELL", "10", "1"),
        )
    }

    @Test
    fun `an event chain stream beside the slot leaves the trades unchanged`(
        @TempDir dir: Path,
    ) {
        val (code, output) =
            backtest(
                dir,
                seed(dir),
                "    breaks = CHAIN:KALSHI.KXS.ladder_breaks.front EVERY 1m",
                "    WHEN breaks.close > 2\n    THEN LOG \"broken ladder\"",
            )

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        assertThat(number(output, "totalPnL")).isEqualByComparingTo("9.99")
    }
}
