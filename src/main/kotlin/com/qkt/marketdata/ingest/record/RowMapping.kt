package com.qkt.marketdata.ingest.record

import com.qkt.marketdata.ingest.map.FieldExpr
import com.qkt.marketdata.ingest.map.SymbolConstants
import com.qkt.marketdata.ingest.parse.NumberStyle
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.PriceBasis
import com.qkt.marketdata.ingest.rows.CellRef
import com.qkt.marketdata.ingest.rows.ImportRow
import java.math.BigDecimal

/**
 * One input's compiled mapping: which cells feed which role, how numbers and times read, and the
 * per-kind rules (price basis, bar label shift, availability). Built once per file, because headers may
 * differ between files of one dataset (Binance added a header row to its futures archives in 2025).
 */
class RowMapping(
    val kind: IngestKind,
    val time: RowTime,
    val fields: Map<String, FieldExpr>,
    val style: NumberStyle,
    val constants: SymbolConstants,
    val basis: PriceBasis?,
    /** Milliseconds to subtract from a close-labelled bar's time; 0 for open-labelled sources. */
    val labelShiftMs: Long = 0,
    /** Milliseconds between a row's time and when it became known (open interest, series). */
    val availableLagMs: Long = 0,
    /** Series: when each value became available, if the source says. */
    val available: RowTime? = null,
    val carryForward: Boolean = false,
    /** Prices are float32 values widened to a double; see [com.qkt.marketdata.ingest.plan.ImportDescriptor.float32]. */
    val float32: Boolean = false,
    val where: List<Pair<CellRef, String>> = emptyList(),
) {
    /** True when [row] passes the mapping's row filter. */
    fun keeps(row: ImportRow): Boolean = where.all { (ref, value) -> row.cell(ref)?.trim() == value }

    /** The exact value of [role] on [row], or null when the source has none for it. */
    fun value(
        role: String,
        row: ImportRow,
    ): BigDecimal? = fields[role]?.eval(row, style, constants)
}
