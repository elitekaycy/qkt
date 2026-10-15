package com.qkt.marketdata.ingest.map

import com.qkt.common.Money
import com.qkt.marketdata.ingest.parse.DecimalText
import com.qkt.marketdata.ingest.parse.NumberStyle
import com.qkt.marketdata.ingest.rows.CellRef
import com.qkt.marketdata.ingest.rows.ImportRow
import java.math.BigDecimal

/** Instrument constants a mapping may use: `point` (the price step, 10^-digits) and `digits`. */
data class SymbolConstants(
    val point: BigDecimal?,
    val digits: Int?,
)

/**
 * A field's value computed from a row: a cell, a constant, a symbol constant, or `+ - * /` of those, in
 * exact [BigDecimal] arithmetic (division at [Money.CONTEXT]). Any absent cell makes the whole value absent,
 * so `bid + spread * point` with no spread is "no ask", never `bid`.
 */
sealed interface FieldExpr {
    /** The value on [row], or null when a cell it reads is absent; a non-number cell throws [NumberFormatException]. */
    fun eval(
        row: ImportRow,
        style: NumberStyle,
        constants: SymbolConstants,
    ): BigDecimal?

    /** Every cell this expression reads. */
    val cells: List<CellRef>

    data class Cell(
        val ref: CellRef,
    ) : FieldExpr {
        override val cells: List<CellRef> get() = listOf(ref)

        override fun eval(
            row: ImportRow,
            style: NumberStyle,
            constants: SymbolConstants,
        ): BigDecimal? = row.cell(ref)?.let { DecimalText.parse(it, style) }
    }

    /** An optional column (`Volume?`) the file does not have: always absent. */
    data object Missing : FieldExpr {
        override val cells: List<CellRef> get() = emptyList()

        override fun eval(
            row: ImportRow,
            style: NumberStyle,
            constants: SymbolConstants,
        ): BigDecimal? = null
    }

    data class Constant(
        val value: BigDecimal,
    ) : FieldExpr {
        override val cells: List<CellRef> get() = emptyList()

        override fun eval(
            row: ImportRow,
            style: NumberStyle,
            constants: SymbolConstants,
        ): BigDecimal = value
    }

    data class Symbol(
        val name: String,
    ) : FieldExpr {
        override val cells: List<CellRef> get() = emptyList()

        override fun eval(
            row: ImportRow,
            style: NumberStyle,
            constants: SymbolConstants,
        ): BigDecimal =
            when (name) {
                "point" ->
                    constants.point
                        ?: error("the mapping uses 'point' but the instrument has no known digits; pass --digits")
                else ->
                    constants.digits?.toBigDecimal()
                        ?: error("the mapping uses 'digits' but the instrument has none; pass --digits")
            }
    }

    data class Binary(
        val op: Char,
        val left: FieldExpr,
        val right: FieldExpr,
    ) : FieldExpr {
        override val cells: List<CellRef> get() = left.cells + right.cells

        override fun eval(
            row: ImportRow,
            style: NumberStyle,
            constants: SymbolConstants,
        ): BigDecimal? {
            val a = left.eval(row, style, constants) ?: return null
            val b = right.eval(row, style, constants) ?: return null
            return when (op) {
                '+' -> a.add(b)
                '-' -> a.subtract(b)
                '*' -> a.multiply(b)
                else -> {
                    if (b.signum() == 0) throw NumberFormatException("division by zero")
                    a.divide(b, Money.CONTEXT)
                }
            }
        }
    }
}
