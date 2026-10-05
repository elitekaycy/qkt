package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.ListedContract
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.BinaryBarStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A root whose contracts only have daily bars built from a vendor archive, on a venue qkt has no bar
 * source for (qkt#1345): `--rolls --tf 1d` measures its history from what is stored.
 */
class RollsFetchDailyTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** Weekday daily bars of [contract] closing at [close], [from] to [to] inclusive, in the binary store. */
    private fun storeDaily(
        store: BinaryBarStore,
        contract: String,
        from: LocalDate,
        to: LocalDate,
        close: String,
    ) {
        for (day in from.datesUntil(to.plusDays(1))) {
            if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) continue
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val px = BigDecimal(close)
            val bar = Candle("CME:$contract", px, px, px, px, BigDecimal.TEN, start, start + 86_400_000L)
            store.writeDay("CME", contract, TimeWindow.ONE_DAY, day, listOf(bar))
        }
    }

    @Test
    fun `a daily-only root's history is measured from its stored daily bars`(
        @TempDir dir: Path,
    ) {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "futures:\n  - { root: CME:CL, currency: USD, multiplier: 1000, tickSize: 0.01, volumeStep: 1, " +
                "volumeMin: 1, roll: { daysBeforeExpiry: 7, atUtc: '00:00', adjust: panama } }\n",
        )
        // CLK20 expires Tuesday 2020-04-21 and rolls Tuesday 04-14; CLM20 expires Tuesday 05-19 and
        // rolls Tuesday 05-12. CLN20's roll falls on Monday 06-15, priced by Friday's daily close.
        val contracts =
            listOf(
                ListedContract("CLK20", ms("2020-04-21T21:00:00Z")),
                ListedContract("CLM20", ms("2020-05-19T21:00:00Z")),
                ListedContract("CLN20", ms("2020-06-22T21:00:00Z")),
                ListedContract("CLQ20", ms("2020-07-21T21:00:00Z")),
            )
        ContractCatalogStore(dir).write(ContractCatalog("CME:CL", contracts))
        val store = BinaryBarStore(dir)
        val start = LocalDate.parse("2020-03-01")
        listOf("CLK20" to "20.00", "CLM20" to "25.00", "CLN20" to "31.00", "CLQ20" to "33.00").forEach { (c, px) ->
            storeDaily(store, c, start, LocalDate.parse("2020-07-21"), px)
        }

        val code =
            FetchCommand(Args(arrayOf("fetch", "CME:CL", "--rolls", "--tf", "1d", "--data-root", dir.toString()))).run()

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        // Binary bars store prices at a fixed scale, so compare the measured prices as numbers.
        val rolls =
            RollHistoryStore(dir).read("CME:CL")?.rolls.orEmpty().map {
                RollRecord(
                    it.atMs,
                    it.from,
                    it.to,
                    it.fromPriceValue().stripTrailingZeros().toPlainString(),
                    it.toPriceValue().stripTrailingZeros().toPlainString(),
                )
            }
        assertThat(rolls).contains(
            RollRecord(ms("2020-04-14T00:00:00Z"), "CLK20", "CLM20", "20", "25"),
            RollRecord(ms("2020-05-12T00:00:00Z"), "CLM20", "CLN20", "25", "31"),
            RollRecord(ms("2020-06-15T00:00:00Z"), "CLN20", "CLQ20", "31", "33"),
        )
    }
}
