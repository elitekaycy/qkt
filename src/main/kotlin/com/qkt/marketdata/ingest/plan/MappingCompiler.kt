package com.qkt.marketdata.ingest.plan

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.clock.ImportClock
import com.qkt.marketdata.ingest.clock.WeeklyOpens
import com.qkt.marketdata.ingest.map.FieldExpr
import com.qkt.marketdata.ingest.map.SymbolConstants
import com.qkt.marketdata.ingest.parse.NumberStyle
import com.qkt.marketdata.ingest.parse.TimeFormat
import com.qkt.marketdata.ingest.record.RowMapping
import com.qkt.marketdata.ingest.record.RowTime
import com.qkt.marketdata.ingest.rows.InputLayout

/**
 * Compiles a resolved [ImportDescriptor] against one input's layout into the [RowMapping] its rows are
 * read through. Everything that can be checked before reading is checked here: the roles a kind needs,
 * every column a mapping names, the timeframe a close label or a lag needs.
 */
class MappingCompiler(
    private val descriptor: ImportDescriptor,
    private val target: ImportTarget,
) {
    /** The kind being imported; a descriptor without one refuses. */
    val kind: IngestKind =
        descriptor.kind
            ?: throw ImportRefusal(
                "no preset fits and no --kind was given (ticks, bars, funding, open-interest, series)",
            )

    /** The mapping for [layout], times read as [format] (epoch unit resolved) on [clock], feeding [opens]. */
    fun compile(
        layout: InputLayout,
        format: TimeFormat,
        clock: ImportClock?,
        opens: WeeklyOpens?,
        timeframeMs: Long?,
    ): RowMapping {
        val names = CellNames(layout, target.bare)
        val fields = descriptor.fields.mapValues { (role, text) -> field(names, role, text) }
        requireRoles(fields, layout)
        if (descriptor.time.isEmpty()) {
            throw ImportRefusal(
                "say which column holds the time: --map time=<column>[+<column>]",
            )
        }
        val time = RowTime(descriptor.time.map { cell(names, it) }, format, clock, opens)
        val shift =
            if (descriptor.label == BarLabel.CLOSE) {
                timeframeMs ?: throw ImportRefusal("close-labelled bars need their timeframe: pass --tf")
            } else {
                0L
            }
        return RowMapping(
            kind = kind,
            time = time,
            fields = fields,
            style = NumberStyle(decimal = (layout as? InputLayout.Csv)?.dialect?.decimal ?: '.'),
            constants = SymbolConstants(target.point, target.digits),
            basis = descriptor.basis,
            labelShiftMs = shift,
            availableLagMs = descriptor.availableLag?.let(::lagMs) ?: 0,
            available =
                descriptor.available?.let {
                    RowTime(listOf(cell(names, it)), TimeFormat.parse(descriptor.availableFormat ?: format.spec), clock)
                },
            carryForward = descriptor.carryForward == true,
            float32 = descriptor.float32 == true,
            where =
                descriptor.where.map { (name, value) ->
                    cell(names, name) to
                        value.replace("\${symbol}", target.bare)
                },
        )
    }

    private fun field(
        names: CellNames,
        role: String,
        text: String,
    ): FieldExpr {
        if (role !in
            kind.roles
        ) {
            throw ImportRefusal("'$role' is not a ${kind.spec} field (fields: ${kind.roles.joinToString()})")
        }
        return try {
            names.expr(text)
        } catch (e: IllegalArgumentException) {
            throw ImportRefusal("cannot map $role from '$text': ${e.message}")
        } catch (e: NumberFormatException) {
            throw ImportRefusal("cannot map $role from '$text': ${e.message}")
        }
    }

    private fun cell(
        names: CellNames,
        name: String,
    ) = try {
        names.cell(name)
    } catch (e: IllegalArgumentException) {
        throw ImportRefusal(e.message ?: "no column '$name'")
    }

    private fun requireRoles(
        fields: Map<String, FieldExpr>,
        layout: InputLayout,
    ) {
        fun has(role: String) = fields[role]?.let { it != FieldExpr.Missing } == true
        val missing =
            when (kind) {
                IngestKind.BARS -> listOf("open", "high", "low", "close").filterNot(::has)
                IngestKind.TICKS ->
                    if (has("price") ||
                        (has("bid") && has("ask"))
                    ) {
                        emptyList()
                    } else {
                        listOf("price, or bid and ask")
                    }
                IngestKind.FUNDING -> listOf("rate").filterNot(::has)
                IngestKind.OPEN_INTEREST -> listOf("openInterest").filterNot(::has)
                IngestKind.SERIES -> listOf("value").filterNot(::has)
            }
        if (missing.isNotEmpty()) {
            throw ImportRefusal(
                "map ${missing.joinToString()} with --map <field>=<column>" +
                    (
                        layout.names?.let { " (columns: ${it.joinToString()})" }
                            ?: " (columns are \$0, \$1, … in a headerless file)"
                    ),
            )
        }
        if (kind == IngestKind.SERIES && descriptor.available == null && descriptor.availableLag == null) {
            throw ImportRefusal(
                "a series needs the time each value became usable: " +
                    "--available <column> or --available-lag <duration>; " +
                    "stamping a published value on the date it describes leaks the future into a backtest",
            )
        }
    }

    /** Milliseconds in a lag such as `10m` or `3d`. */
    fun lagMs(text: String): Long =
        try {
            TimeWindow.parse(text.trim()).durationMs
        } catch (e: IllegalArgumentException) {
            throw ImportRefusal("invalid duration '$text' (e.g. 10m, 6h, 3d): ${e.message}")
        }
}
