package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.InputLine
import com.qkt.marketdata.ingest.source.ImportInput
import com.qkt.marketdata.ingest.source.TextDecoding
import java.nio.charset.Charset
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Streams the rows of a JSON input: an array of rows (`[[t,o,h,l,c,v],…]` from ccxt or Binance, or an
 * array of objects), newline-delimited objects (NDJSON, one row a line), or the array or object found at
 * [rowsPath] inside one document (`candles` for Oanda, `results` for Polygon). An object of rows (Alpha
 * Vantage keys each bar by its time) yields one row per entry, its key readable as `@key`.
 */
internal object JsonRows {
    private val json = Json { isLenient = false }

    /** True when [firstLine] starts a JSON document rather than CSV. */
    fun isJson(firstLine: String): Boolean = firstLine.trimStart().let { it.startsWith('[') || it.startsWith('{') }

    /** Calls [onRow] for each row of [input]. */
    fun read(
        input: ImportInput,
        rowsPath: List<String>,
        charset: Charset?,
        onRow: (ImportRow) -> Unit,
    ) {
        val text = TextDecoding.open(input.open(), charset).use { it.readText() }
        val lines = text.lines().filter { it.isNotBlank() }
        if (rowsPath.isEmpty() && lines.size > 1 && lines.all { it.trimStart().startsWith('{') }) {
            lines.forEachIndexed { i, line ->
                onRow(
                    JsonRow(
                        InputLine(input.name, i + 1L, line),
                        parse(
                            line,
                            input,
                            i + 1L,
                        ),
                    ),
                )
            }
            return
        }
        var node: JsonElement = parse(text, input, 1)
        for (segment in rowsPath) {
            node =
                (node as? JsonObject)?.get(segment)
                    ?: throw ImportRefusal("${input.name} has no '${rowsPath.joinToString(".")}' to read rows from")
        }
        when (node) {
            is JsonArray ->
                node.forEachIndexed { i, row -> onRow(JsonRow(InputLine(input.name, i + 1L, row.toString()), row)) }
            is JsonObject ->
                node.entries.forEachIndexed { i, (key, row) ->
                    onRow(JsonRow(InputLine(input.name, i + 1L, "$key: $row"), row, key))
                }
            else -> throw ImportRefusal("${input.name}: rows must be a JSON array or object, found $node")
        }
    }

    private fun parse(
        text: String,
        input: ImportInput,
        line: Long,
    ): JsonElement =
        try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ImportRefusal("${input.name}:$line is not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        }
}
