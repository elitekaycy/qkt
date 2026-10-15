package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.InputLine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where a value sits in a row: column [Index] (CSV columns, cells of an array row such as a Binance
 * kline), or a [Path] of object keys and array positions (`mid.o` in an Oanda candle). `@key` as the
 * first segment is the key a row was listed under in an object of rows (Alpha Vantage's time series).
 */
sealed interface CellRef {
    data class Index(
        val index: Int,
    ) : CellRef

    data class Path(
        val segments: List<String>,
    ) : CellRef
}

/** One input row: its cells by [CellRef], and [where] it came from for messages. */
interface ImportRow {
    val where: InputLine

    /** The cell's text, or null when the row has no such cell (or holds JSON null there). */
    fun cell(ref: CellRef): String?
}

internal class CsvRow(
    override val where: InputLine,
    private val cells: List<String>,
) : ImportRow {
    override fun cell(ref: CellRef): String? = (ref as? CellRef.Index)?.let { cells.getOrNull(it.index) }
}

internal class JsonRow(
    override val where: InputLine,
    private val element: JsonElement,
    private val key: String? = null,
) : ImportRow {
    override fun cell(ref: CellRef): String? {
        val segments =
            when (ref) {
                is CellRef.Index -> listOf(ref.index.toString())
                is CellRef.Path -> ref.segments
            }
        if (segments.firstOrNull() == KEY) return key
        var node: JsonElement? = element
        for (segment in segments) {
            node =
                when (val current = node) {
                    is JsonObject -> current[segment]
                    is JsonArray -> segment.toIntOrNull()?.let { current.getOrNull(it) }
                    else -> null
                }
        }
        return when (val leaf = node) {
            null, JsonNull -> null
            is JsonPrimitive -> leaf.content
            else -> null
        }
    }

    /** The keys of an object row, or null for an array row. */
    fun keys(): List<String>? = (element as? JsonObject)?.keys?.toList()

    /** Cells in an array row, keys in an object row. */
    fun width(): Int =
        when (element) {
            is JsonArray -> element.size
            is JsonObject -> element.size
            else -> 1
        }

    /** The first primitive value of an object row, as text. */
    fun firstValue(): String? = (element as? JsonObject)?.values?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }

    companion object {
        const val KEY = "@key"
    }
}
