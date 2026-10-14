package com.qkt.marketdata.ingest.run

import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.parse.TimeFormat
import com.qkt.marketdata.ingest.parse.TimeFormatGuess
import com.qkt.marketdata.ingest.plan.CellNames
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.ImportTarget
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.InputShape
import com.qkt.marketdata.ingest.plan.PresetChooser
import com.qkt.marketdata.ingest.plan.PresetLibrary
import com.qkt.marketdata.ingest.rows.InputLayout
import com.qkt.marketdata.ingest.rows.sampleRows
import com.qkt.marketdata.ingest.source.ImportInput
import com.qkt.marketdata.ingest.source.ImportSources
import java.nio.file.Path

/** The resolved plan for an import: its inputs, how each is laid out and timed, and the descriptor. */
data class ImportPlan(
    val descriptor: ImportDescriptor,
    val presetSource: String,
    val target: ImportTarget,
    val inputs: List<ImportInput>,
    val layouts: List<InputLayout>,
    val formats: List<TimeFormat>,
    /** Files a directory walk passed over as not data. */
    val skippedFiles: List<Path> = emptyList(),
)

/**
 * Resolves what an import will do before any row is read in full: expands the inputs, matches a preset
 * (unless one is named), overlays the user's flags, lays out every input, and fixes each input's time
 * format (an epoch unit is decided per file, because vendors change units between files).
 */
class ImportPlanner(
    private val registry: InstrumentRegistry,
    private val latestMs: Long,
) {
    /** The plan for [request]. */
    fun plan(request: ImportRequest): ImportPlan {
        val expansion = ImportSources.expand(request.paths)
        val inputs = expansion.inputs
        if (inputs.isEmpty()) {
            throw ImportRefusal(
                "nothing to import (skipped: ${expansion.skipped.joinToString { it.fileName.toString() }})",
            )
        }
        val o = request.overrides
        val first = inputs.first()
        val hint = InputLayout.of(first, o.json, o.rows, o.delimiter, o.decimal, request.charset)
        val (base, source) =
            when {
                request.preset != null -> PresetLibrary.resolve(request.preset) to "--preset ${request.preset}"
                else -> choosePreset(first, hint, request)?.let { it to "matched" } ?: (ImportDescriptor() to "flags")
            }
        val descriptor = base.overlaidBy(o)
        val layouts =
            inputs.map {
                InputLayout.of(
                    it,
                    descriptor.json,
                    descriptor.rows,
                    descriptor.delimiter,
                    descriptor.decimal,
                    request.charset,
                )
            }
        val target = ImportTarget.of(request.symbol, registry, descriptor.digits)
        descriptor.kind?.let { requireSymbolFits(it, target) }
        val stated = descriptor.timeFormat?.let { parseFormat(it) }
        val formats = inputs.indices.map { i -> formatFor(inputs[i], layouts[i], descriptor, stated) }
        return ImportPlan(descriptor, source, target, inputs, layouts, formats, expansion.skipped)
    }

    private fun choosePreset(
        first: ImportInput,
        hint: InputLayout,
        request: ImportRequest,
    ): ImportDescriptor? {
        val layouts = mutableMapOf<List<String>, InputLayout?>()
        return PresetChooser.choose { preset ->
            if (hint !is InputLayout.Json || preset.rows.isEmpty() || request.overrides.rows.isNotEmpty()) {
                shape(first, hint)
            } else {
                layouts
                    .getOrPut(preset.rows) {
                        try {
                            InputLayout.of(first, true, preset.rows, null, null, request.charset)
                        } catch (_: ImportRefusal) {
                            null
                        }
                    }?.let { shape(first, it) }
            }
        }
    }

    private fun formatFor(
        input: ImportInput,
        layout: InputLayout,
        descriptor: ImportDescriptor,
        stated: TimeFormat?,
    ): TimeFormat {
        if (descriptor.time.isEmpty()) return stated ?: TimeFormat.Iso
        val names = CellNames(layout)
        val cells =
            descriptor.time.map {
                try {
                    names.cell(it)
                } catch (e: IllegalArgumentException) {
                    throw ImportRefusal("${input.name}: ${e.message}")
                }
            }
        val samples =
            sampleRows(layout, input, SAMPLE).mapNotNull { row ->
                val parts = cells.map { row.cell(it)?.trim() }
                if (parts.any { it == null }) null else parts.joinToString(" ").takeIf { it.isNotBlank() }
            }
        if (samples.isEmpty()) throw ImportRefusal("${input.name} has no timestamps in its first rows")
        return try {
            TimeFormatGuess.resolve(stated, samples, latestMs)
        } catch (e: IllegalArgumentException) {
            throw ImportRefusal("${input.name}: ${e.message}")
        }
    }

    private fun parseFormat(text: String): TimeFormat =
        try {
            TimeFormat.parse(text)
        } catch (e: IllegalArgumentException) {
            throw ImportRefusal(e.message ?: "invalid --time-format '$text'")
        }

    private fun requireSymbolFits(
        kind: IngestKind,
        target: ImportTarget,
    ) {
        if (kind == IngestKind.TICKS) return
        val source =
            target.source ?: throw ImportRefusal("${kind.spec} need a source prefix: --as SOURCE:${target.bare}")
        if (source == "BACKTEST" && (kind == IngestKind.BARS || kind == IngestKind.SERIES)) {
            throw ImportRefusal(
                "BACKTEST names the bars qkt data build-bars makes; import under the data's own source, e.g. --as EXNESS:${target.bare}",
            )
        }
    }

    private fun shape(
        input: ImportInput,
        layout: InputLayout,
    ) = InputShape(
        fileName = input.name,
        json = layout is InputLayout.Json,
        delimiter = (layout as? InputLayout.Csv)?.dialect?.delimiter,
        header = layout.names,
        columns = layout.columns,
        firstCell = layout.firstCell,
    )

    private companion object {
        const val SAMPLE = 1000
    }
}
