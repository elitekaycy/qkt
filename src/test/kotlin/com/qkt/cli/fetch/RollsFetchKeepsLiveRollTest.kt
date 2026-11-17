package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.ListedContract
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `qkt fetch --rolls` keeps every record already in the history and only appends rolls after it (#1754). A record is
 * the reference a live session booked its positions and `cost:roll` against: re-pricing it would move the series
 * every strategy reads by a jump the market never made. A live record the stored bars cannot price was always kept;
 * now one they could price is kept too. Re-pricing runs only behind `--remeasure`, which warns that a running session
 * will book the move as a series rebase.
 */
class RollsFetchKeepsLiveRollTest {
    private val september = Instant.parse("2024-09-19T08:00:00Z").toEpochMilli()
    private val december = Instant.parse("2024-12-19T08:00:00Z").toEpochMilli()
    private val codes = listOf("BTCUSDT_240927", "BTCUSDT_241227", "BTCUSDT_250328")
    private val live = RollRecord(december, codes[1], codes[2], "97000", "98500")
    private val stale = RollRecord(september, codes[0], codes[1], "1", "1")

    /** A root whose stored bars price the September roll at 63000/63800, over a history holding [stale] and [live]. */
    private fun seed(dir: Path) {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "futures:\n  - { root: BINANCE_UM:BTCUSDT, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, volumeMin: 0.001, " +
                "roll: { daysBeforeExpiry: 8, atUtc: '08:00', adjust: panama } }\n",
        )
        val expiries = listOf("2024-09-27", "2024-12-27", "2025-03-28")
        ContractCatalogStore(dir).write(
            ContractCatalog(
                "BINANCE_UM:BTCUSDT",
                codes.zip(expiries).map { (c, d) -> ListedContract(c, Instant.parse("${d}T08:00:00Z").toEpochMilli()) },
            ),
        )
        val day = LocalDate.parse("2024-09-19")
        val bars = LocalBarStore(dir)
        for ((code, close) in listOf(codes[0] to "63000", codes[1] to "63800")) {
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 28_740_000L
            val px = BigDecimal(close)
            val bar = Candle("BINANCE_UM:$code", px, px, px, px, BigDecimal.ONE, start, start + 60_000)
            bars.writeDay("BINANCE_UM", code, "1m", day, listOf(bar))
            bars.recordDay("BINANCE_UM", code, "1m", day)
        }
        RollHistoryStore(dir).write(RollHistory("BINANCE_UM:BTCUSDT", "8d@08:00", listOf(stale, live)))
    }

    /** Runs the fetch quietly; returns its exit code and what it wrote to stderr. */
    private fun fetch(
        dir: Path,
        remeasure: Boolean,
    ): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val code =
            PrintStream(ByteArrayOutputStream()).use { quiet ->
                val (out, errOut) = System.out to System.err
                try {
                    System.setOut(quiet)
                    System.setErr(PrintStream(err))
                    RollsFetch.run(
                        "BINANCE_UM:BTCUSDT",
                        dir,
                        null,
                        nowMs = december + 86_400_000L,
                        remeasure = remeasure,
                    ) { _, _ -> }
                } finally {
                    System.setOut(out)
                    System.setErr(errOut)
                }
            }
        return code to err.toString()
    }

    @Test
    fun `a fetch keeps every record already in the history, even one the stored bars would price otherwise`(
        @TempDir dir: Path,
    ) {
        seed(dir)

        val (code, err) = fetch(dir, remeasure = false)

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(RollHistoryStore(dir).read("BINANCE_UM:BTCUSDT")!!.rolls).containsExactly(stale, live)
        assertThat(err).doesNotContain("series rebase")
    }

    @Test
    fun `--remeasure prices again what the bars can, keeps what they cannot, and warns of the rebase`(
        @TempDir dir: Path,
    ) {
        seed(dir)

        val (code, err) = fetch(dir, remeasure = true)

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val history = RollHistoryStore(dir).read("BINANCE_UM:BTCUSDT")!!
        assertThat(history.find(december, codes[1], codes[2])).isEqualTo(live)
        assertThat(history.find(september, codes[0], codes[1])?.toPrice).isEqualTo("63800")
        assertThat(err).contains("--remeasure").contains("series rebase")
    }
}
