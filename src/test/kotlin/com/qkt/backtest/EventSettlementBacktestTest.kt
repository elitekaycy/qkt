package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import com.qkt.common.FixedClock
import com.qkt.instrument.EventCatalogStore
import com.qkt.instrument.EventFamilyFixture
import com.qkt.marketdata.ingest.record.TickRecord
import com.qkt.marketdata.ingest.store.TickDayTarget
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The event family `KALSHI:KXT` (two events of three strikes) backtested through the full `qkt backtest` command,
 * from quote ticks. The strategy buys 10 of E1's 200 strike at the 0.42 ask (it resolves YES at 12:00Z) and 10 of
 * E2's 200 strike at the 0.32 ask (it resolves NO at 18:00Z). Each settles at its catalogued outcome, never its last
 * quote, and each entry pays the 7% probability fee rounded up to the cent:
 *
 * - E1: roundUp(0.07 x 10 x 0.42 x 0.58 = 0.17052) = 0.18 fee; settles at 1, gross +5.80.
 * - E2: roundUp(0.07 x 10 x 0.32 x 0.68 = 0.15232) = 0.16 fee; settles at 0, gross -3.20.
 * - Net: 5.80 - 3.20 - 0.34 = 2.26; a settlement at 1 or 0 pays no fee.
 */
class EventSettlementBacktestTest {
    private val yes = "KXT-E1-T2"
    private val no = "KXT-E2-T2"

    private fun seed(
        dir: Path,
        outcomes: Map<String, String>,
    ): Path {
        val data = dir.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("instruments.yaml"),
            "events:\n  - { root: KALSHI:KXT, currency: USD, tickSize: 0.01, volumeStep: 1, volumeMin: 1 }\n",
        )
        EventCatalogStore(data).write(EventFamilyFixture.catalog(outcomes))
        quotes(data, "KXT_E1_T2", "10:00" to "0.40/0.42", "10:31" to "0.90/0.92", "11:59" to "")
        quotes(data, "KXT_E2_T2", "10:00" to "0.30/0.32", "10:31" to "0.05/0.07", "18:00" to "")
        return data
    }

    /** One quote a minute for [bare]: each step's bid/ask from its start until the next step, the last step ending. */
    private fun quotes(
        data: Path,
        bare: String,
        vararg steps: Pair<String, String>,
    ) {
        val day = LocalDate.parse("2024-03-04")
        val records = mutableListOf<TickRecord>()
        for ((step, next) in steps.toList().zipWithNext()) {
            val (bid, ask) = step.second.split('/').map(::BigDecimal)
            var at = EventFamilyFixture.ms("${day}T${step.first}:00Z")
            val end = EventFamilyFixture.ms("${day}T${next.first}:00Z")
            val last = next == steps.last()
            while (at < end || (last && at == end)) {
                records += TickRecord(at, records.size.toLong(), null, null, bid, ask, null, null)
                at += 60_000L
            }
        }
        val target = TickDayTarget(data, bare, FixedClock(0L))
        target.write(day, records)
        target.commit(listOf(day))
    }

    private fun backtest(
        dir: Path,
        data: Path,
    ): Pair<Int, String> {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY ev VERSION 1
            SYMBOLS
                a = KALSHI:KXT_E1_T2 EVERY 1m
                b = KALSHI:KXT_E2_T2 EVERY 1m
            RULES
                WHEN a.close < 0.5 AND POSITION.a = 0
                THEN BUY a SIZING 10
                WHEN b.close > 0.25 AND b.close < 0.5 AND POSITION.b = 0
                THEN BUY b SIZING 10
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

    @Test
    fun `a YES and a NO contract settle at their outcomes with exact fees`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, mapOf(yes to "1", no to "0")))

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        assertThat(number(output, "commissionPaid")).isEqualByComparingTo("0.34")
        assertThat(number(output, "finalRealized")).isEqualByComparingTo("2.26")
        assertThat(number(output, "finalUnrealized")).isEqualByComparingTo("0")
        assertThat(number(output, "totalPnL")).isEqualByComparingTo("2.26")
        val settlements = Files.readAllLines(dir.resolve("report/settlements.csv")).drop(1).map { it.split(',') }
        assertThat(settlements.map { Triple(it[2], it[3], BigDecimal(it[5]).stripTrailingZeros().toPlainString()) })
            .containsExactly(
                Triple("KALSHI:KXT_E1_T2", "SELL", "1"),
                Triple("KALSHI:KXT_E2_T2", "SELL", "0"),
            )
        assertThat(settlements.map { BigDecimal(it[4]) }).allMatch { it.compareTo(BigDecimal.TEN) == 0 }
        assertThat(settlements.map { it[0].toLong() })
            .containsExactly(EventFamilyFixture.E1_EXPIRY_MS, EventFamilyFixture.E2_EXPIRY_MS)
    }

    @Test
    fun `a held contract that expired with no outcome fails the run naming it`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, seed(dir, mapOf(yes to "1")))

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("KALSHI:KXT_E2_T2").contains("no outcome")
    }
}
