package com.qkt.marketdata.store.macro

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory

/**
 * Parses a central bank's XLSX statistical table: finds the first sheet with a column headed
 * [valueHeader], and reads every dated numeric row of it as a [MacroPoint] stamped with
 * [availableAt]. Rows are de-duplicated by date and sorted; a workbook without such a series
 * is an error.
 */
internal fun parseOfficialWorkbook(
    bytes: ByteArray,
    valueHeader: String,
    availableAt: (LocalDate) -> Long,
): List<MacroPoint> =
    WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
        for (sheet in workbook) {
            var valueColumn: Int? = null
            for (row in sheet) {
                for (cell in row) {
                    if (cell.cellType == CellType.STRING && cell.stringCellValue.contains(valueHeader, true)) {
                        valueColumn = cell.columnIndex
                        break
                    }
                }
                if (valueColumn != null) break
            }
            val column = valueColumn ?: continue
            val points =
                sheet.mapNotNull { row ->
                    val dateCell = row.getCell(0) ?: return@mapNotNull null
                    val valueCell = row.getCell(column) ?: return@mapNotNull null
                    if (dateCell.cellType != CellType.NUMERIC || !DateUtil.isCellDateFormatted(dateCell)) {
                        return@mapNotNull null
                    }
                    if (valueCell.cellType != CellType.NUMERIC) return@mapNotNull null
                    val date = dateCell.localDateTimeCellValue.toLocalDate()
                    MacroPoint(date, BigDecimal(valueCell.numericCellValue.toString()), availableAt(date))
                }
            if (points.isNotEmpty()) return@use points.distinctBy { it.date }.sortedBy { it.date }
        }
        error("official workbook does not contain a dated '$valueHeader' series")
    }
