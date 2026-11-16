package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.store.DayPrices
import com.qkt.marketdata.ingest.store.DayTarget
import com.qkt.marketdata.ingest.store.ImportProvenance
import com.qkt.marketdata.ingest.store.PricePoint
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SeamReportTest {
    private val stored = LocalDate.of(2026, 1, 8)
    private val written = LocalDate.of(2026, 1, 9)

    private val target =
        object : DayTarget {
            override val dir: Path = Path.of("unused")

            override fun stored(day: LocalDate): List<Path> = if (day == stored) listOf(Path.of("d")) else emptyList()

            override fun write(
                day: LocalDate,
                records: List<IngestRecord>,
            ) = Unit

            override fun readStored(day: LocalDate): DayPrices =
                if (day == stored) DayPrices(listOf(PricePoint(1_000L, BigDecimal("10")))) else DayPrices(emptyList())

            override fun commit(days: Collection<LocalDate>) = Unit
        }

    @Test
    fun `a neighbouring stored day no import wrote is named as not imported`() {
        val seams =
            SeamReport(target, null).seams(
                mapOf(written to DayPrices(listOf(PricePoint(2_000L, BigDecimal("12"))))),
                "this import",
                ImportProvenance(),
                "id-1",
            )

        assertThat(seams.single())
            .contains("2026-01-08 → 2026-01-09")
            .contains("data not imported (fetched or hand-written)")
            .contains("this import")
            .contains("price step 2")
    }
}
