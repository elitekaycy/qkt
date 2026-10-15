package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.source.ImportInput

/** The first [count] rows of [input], read without streaming the rest of the file. */
internal fun sampleRows(
    layout: InputLayout,
    input: ImportInput,
    count: Int,
): List<ImportRow> {
    val rows = mutableListOf<ImportRow>()
    try {
        layout.read(input) { row ->
            rows += row
            if (rows.size >= count) throw SampleFull()
        }
    } catch (_: SampleFull) {
        return rows
    }
    return rows
}

private class SampleFull : RuntimeException(null, null, false, false)
