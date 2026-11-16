package com.qkt.marketdata.store.macro

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A central bank workbook yields only its dated numeric rows, and one without them is an error. */
class OfficialRateWorkbookTest {
    private fun bytes(build: XSSFWorkbook.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { out ->
            XSSFWorkbook().use {
                it.build()
                it.write(out)
            }
            out.toByteArray()
        }

    private fun XSSFWorkbook.dated(
        rowIndex: Int,
        date: String,
    ) = getSheet("Data").createRow(rowIndex).also { row ->
        row.createCell(0).apply {
            setCellValue(java.sql.Date.valueOf(date))
            cellStyle = createCellStyle().apply { dataFormat = 14 }
        }
    }

    @Test
    fun `rows without a date, without a value or with text values are passed over`() {
        val book =
            bytes {
                val sheet = createSheet("Data")
                sheet.createRow(0).createCell(1).setCellValue("Cash Rate Target")
                dated(1, "2024-03-04").createCell(1).setCellValue(4.35)
                dated(2, "2024-03-05")
                dated(3, "2024-03-06").createCell(1).setCellValue("n/a")
                sheet.createRow(4).apply {
                    createCell(0).setCellValue(45_000.0)
                    createCell(1).setCellValue(4.10)
                }
                sheet.createRow(5).createCell(1).setCellValue(4.20)
            }

        val points = parseOfficialWorkbook(book, "cash rate target") { 7L }

        assertThat(points.map { it.date to it.value })
            .containsExactly(LocalDate.parse("2024-03-04") to BigDecimal("4.35"))
        assertThat(points.single().availableAtMs).isEqualTo(7L)
    }

    @Test
    fun `a workbook with the header but no dated numbers, or without the header, is an error`() {
        val headerOnly =
            bytes {
                createSheet("Data").createRow(0).createCell(1).setCellValue("Cash Rate Target")
            }
        val noHeader =
            bytes {
                createSheet("Data").createRow(0).createCell(1).setCellValue("Something else")
            }

        for (book in listOf(headerOnly, noHeader)) {
            assertThatThrownBy { parseOfficialWorkbook(book, "Cash Rate Target") { 0L } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("official workbook does not contain a dated 'Cash Rate Target' series")
        }
    }
}
