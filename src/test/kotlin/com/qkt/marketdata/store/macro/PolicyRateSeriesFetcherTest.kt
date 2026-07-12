package com.qkt.marketdata.store.macro

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PolicyRateSeriesFetcherTest {
    @Test
    fun `derives point in time RBA minus RBNZ values from official workbook shapes`(
        @TempDir tmp: Path,
    ) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(workbook("Cash Rate Target", "4.35", "4.10"))))
        server.enqueue(
            MockResponse().setBody(okio.Buffer().write(workbook("Official Cash Rate (OCR)", "5.50", "4.00"))),
        )
        server.start()
        try {
            val store = MacroSeriesStore(tmp)
            val fetcher =
                PolicyRateSeriesFetcher(
                    store = store,
                    rbaSource = server.url("/rba.xlsx").toString(),
                    rbnzSource = server.url("/rbnz.xlsx").toString(),
                )

            fetcher.fetch(
                PolicyRateSeries.RBA_RBNZ_DIFFERENTIAL,
                LocalDate.of(2024, 3, 4),
                LocalDate.of(2024, 3, 5),
            )

            val points =
                store.read(
                    PolicyRateSeries.RBA_RBNZ_DIFFERENTIAL.id,
                    LocalDate.of(2024, 3, 4),
                    LocalDate.of(2024, 3, 5),
                )
            assertThat(points.map { it.value }).containsExactly(BigDecimal("-1.15"), BigDecimal("0.1"))
            val expectedAvailability =
                LocalDate
                    .of(2024, 3, 6)
                    .atTime(15, 0)
                    .atZone(ZoneId.of("Pacific/Auckland"))
                    .toInstant()
                    .toEpochMilli()
            assertThat(points.last().availableAtMs).isEqualTo(expectedAvailability)
            val provenance = store.readProvenance(PolicyRateSeries.RBA_RBNZ_DIFFERENTIAL.id)
            assertThat(provenance?.sources?.keys).containsExactlyInAnyOrder(
                server.url("/rba.xlsx").toString(),
                server.url("/rbnz.xlsx").toString(),
            )
            assertThat(provenance?.sources?.values).allMatch { it.matches(Regex("[0-9a-f]{64}")) }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `reads an operator supplied official artifact and applies the publication lag`(
        @TempDir tmp: Path,
    ) {
        val artifact = tmp.resolve("rbnz.xlsx")
        Files.write(
            artifact,
            workbook(
                header = "Official Cash Rate (OCR)",
                first = "5.50",
                second = "5.25",
                firstDate = "2024-03-07",
                secondDate = "2024-03-08",
            ),
        )
        val store = MacroSeriesStore(tmp.resolve("store"))
        val fetcher = PolicyRateSeriesFetcher(store = store, rbnzSource = artifact.toString())

        fetcher.fetch(
            PolicyRateSeries.RBNZ_OCR,
            LocalDate.of(2024, 3, 7),
            LocalDate.of(2024, 3, 8),
        )

        val points =
            store.read(
                PolicyRateSeries.RBNZ_OCR.id,
                LocalDate.of(2024, 3, 7),
                LocalDate.of(2024, 3, 8),
            )
        assertThat(points.map { it.value }).containsExactly(BigDecimal("5.5"), BigDecimal("5.25"))
        val mondayRelease =
            LocalDate
                .of(2024, 3, 11)
                .atTime(15, 0)
                .atZone(ZoneId.of("Pacific/Auckland"))
                .toInstant()
                .toEpochMilli()
        assertThat(points.last().availableAtMs).isEqualTo(mondayRelease)
        assertThat(store.readProvenance(PolicyRateSeries.RBNZ_OCR.id)?.sources?.keys)
            .containsExactly(artifact.toString())
    }

    private fun workbook(
        header: String,
        first: String,
        second: String,
        firstDate: String = "2024-03-04",
        secondDate: String = "2024-03-05",
    ): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Data")
        sheet.createRow(0).createCell(1).setCellValue(header)
        listOf(firstDate to first, secondDate to second).forEachIndexed { index, (date, value) ->
            val row = sheet.createRow(index + 1)
            val dateCell = row.createCell(0)
            dateCell.setCellValue(java.sql.Date.valueOf(date))
            dateCell.cellStyle = workbook.createCellStyle().apply { dataFormat = 14 }
            row.createCell(1).setCellValue(value.toDouble())
        }
        return ByteArrayOutputStream().use { output ->
            workbook.use { it.write(output) }
            output.toByteArray()
        }
    }
}
