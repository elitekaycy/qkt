package com.qkt.cli.data

import com.qkt.marketdata.ingest.check.CheckTally
import com.qkt.marketdata.ingest.record.FundingRecord
import com.qkt.marketdata.ingest.record.OpenInterestRecord
import com.qkt.marketdata.ingest.record.SeriesRecord
import com.qkt.marketdata.ingest.record.TickRecord
import com.qkt.marketdata.ingest.run.DayOutcomes
import com.qkt.marketdata.ingest.run.ImportReport
import com.qkt.marketdata.ingest.run.InputStats
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ImportReportTextTest {
    private val at = 1_704_326_400_000L

    @Test
    fun `a tick shows only the fields it carries`() {
        val tick =
            TickRecord(
                at,
                0,
                BigDecimal("1850.50"),
                BigDecimal("2.0"),
                BigDecimal("1850.40"),
                BigDecimal("1850.60"),
                null,
                null,
            )
        assertThat(ImportReportText.stored(tick))
            .isEqualTo("2024-01-04T00:00:00Z bid=1850.4 ask=1850.6 price=1850.5 vol=2")

        val bare = TickRecord(at, 0, null, null, BigDecimal("1.1"), null, null, null)
        assertThat(ImportReportText.stored(bare)).isEqualTo("2024-01-04T00:00:00Z bid=1.1")
    }

    @Test
    fun `funding open interest and series records read in their own terms`() {
        assertThat(ImportReportText.stored(FundingRecord(at, 0, BigDecimal("0.000100"), BigDecimal("42000"))))
            .isEqualTo("2024-01-04T00:00:00Z rate=0.0001 price=42000")
        assertThat(ImportReportText.stored(FundingRecord(at, 0, BigDecimal("-0.0002"), null)))
            .isEqualTo("2024-01-04T00:00:00Z rate=-0.0002")
        assertThat(ImportReportText.stored(OpenInterestRecord(at, 0, BigDecimal("1200.0"))))
            .isEqualTo("2024-01-04T00:00:00Z oi=1200")
        assertThat(ImportReportText.stored(SeriesRecord(at, 0, at + 3_600_000L, BigDecimal("3.70"))))
            .isEqualTo("2024-01-04 value=3.7 (observed 2024-01-04T01:00:00Z)")
    }

    @Test
    fun `a dry run with dropped zeros and conflicts says what it would do`() {
        val tally =
            CheckTally().apply {
                zeroOpenInterestDropped = 2
                conflictsKeptLast += "2024-01-04T00:00:00Z 1200 vs 1300"
            }
        val input = InputStats(name = "oi.csv", index = 0, rows = 3)
        val report =
            ImportReport(
                symbol = "BINANCE:BTCUSDT",
                kind = "open-interest",
                preset = "binance-oi",
                presetSource = "detected",
                description = null,
                inputs = listOf(input),
                timeFormats = listOf("epoch-ms"),
                clock = "UTC",
                clockEvidence = null,
                timeframe = null,
                basis = null,
                volume = null,
                target = "data/open_interest/BINANCE/BTCUSDT",
                days = listOf(LocalDate.parse("2024-01-04")),
                records = 1,
                tally = tally,
                outcomes = DayOutcomes(),
                warnings = emptyList(),
                dryRun = true,
                plan = "",
            )

        val text = ImportReportText.render(report)

        assertThat(text.lines())
            .contains(
                "Dropped  2 open-interest figure(s) of exactly 0 (venue outage placeholders)",
                "Conflict 1 time(s) held two values; kept the later file's:",
                "           2024-01-04T00:00:00Z 1200 vs 1300",
                "Writes   nothing (dry run) → data/open_interest/BINANCE/BTCUSDT",
            )
        assertThat(text).doesNotContain("Wrote ")
    }
}
