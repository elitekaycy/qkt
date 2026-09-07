package com.qkt.marketdata.hub

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reader for the hub's append-only journal — the live half of the hub contract.
 *
 * The journal is the same records a snapshot holds, still being appended to, so tailing it live
 * and reading a compiled snapshot for history are the same bytes rather than two pipelines that
 * agree by hope. That is the property the engine's backtest-versus-live parity rests on here.
 *
 * Two rules matter to a caller. A trailing line with no newline is ignored: the writer may be
 * mid-append, and parsing half a record would fabricate a fact. And a malformed line is skipped
 * and counted rather than thrown, because a live tail that dies on one bad byte takes the trading
 * session down with it — the count is what an operator alerts on instead.
 */
class HubJournal(
    private val root: Path,
    private val dataset: String,
) {
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    /** Lines this reader could not parse. Non-zero means the store or the writer needs attention. */
    var skipped: Long = 0L
        private set

    private fun directory(): Path = root.resolve("journal").resolve(dataset)

    private fun dayFiles(): List<Path> {
        val dir = directory()
        if (!Files.isDirectory(dir)) return emptyList()
        Files.list(dir).use { stream ->
            return stream
                .filter { it.fileName.toString().endsWith(".ndjson") }
                .sorted()
                .toList()
        }
    }

    /**
     * Every record whose `known_at` falls in `[fromMs, toMs)`, in journal order.
     *
     * Day files are named by the record's own `known_at`, so a range read only opens the files
     * whose day could contain it — but it still filters, because a file's name bounds the day and
     * not the requested window.
     */
    fun read(
        fromMs: Long,
        toMs: Long,
    ): List<HubRecord> {
        // Day names are compared as strings, so the bounds must be clamped to instants that
        // actually format as `yyyy-MM-dd`. Formatting Long.MAX_VALUE yields a year with a leading
        // `+`, which sorts BELOW every real date and would silently skip every file -- a whole
        // dataset reading as empty rather than failing.
        val fromDay = dayFormat.format(Instant.ofEpochMilli(fromMs.coerceIn(MIN_EPOCH_MS, MAX_EPOCH_MS)))
        val toDay = dayFormat.format(Instant.ofEpochMilli(toMs.coerceIn(MIN_EPOCH_MS, MAX_EPOCH_MS)))
        val out = ArrayList<HubRecord>()
        for (file in dayFiles()) {
            val day = file.fileName.toString().removeSuffix(".ndjson")
            if (day < fromDay || day > toDay) continue
            for (line in completeLines(file)) {
                val record = parse(line) ?: continue
                if (record.knownAt in fromMs until toMs) out.add(record)
            }
        }
        return out
    }

    /** Every record in the dataset, in journal order. Used to seed history before a run. */
    fun readAll(): List<HubRecord> = read(MIN_EPOCH_MS, MAX_EPOCH_MS)

    companion object {
        /** 2000-01-01 and 2100-01-01: the same plausible-timestamp bounds the hub enforces. */
        const val MIN_EPOCH_MS: Long = 946_684_800_000L
        const val MAX_EPOCH_MS: Long = 4_102_444_800_000L
    }

    /**
     * Lines that are definitely complete: a trailing fragment with no newline is left for the
     * next poll, because the writer may still be finishing it.
     */
    private fun completeLines(file: Path): List<String> {
        val bytes = Files.readAllBytes(file)
        if (bytes.isEmpty()) return emptyList()
        val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
        if (lastNewline < 0) return emptyList()
        return String(bytes, 0, lastNewline + 1, StandardCharsets.UTF_8).split('\n').filter { it.isNotBlank() }
    }

    private fun ByteArray.lastIndexOf(target: Byte): Int {
        for (i in indices.reversed()) if (this[i] == target) return i
        return -1
    }

    private fun parse(line: String): HubRecord? =
        try {
            HubJournalCodec.parse(line)
        } catch (e: IllegalArgumentException) {
            skipped++
            null
        } catch (e: IndexOutOfBoundsException) {
            skipped++
            null
        }
}

/**
 * The journal's line format: one JSON object per record.
 *
 * Strict by design -- a missing or wrongly typed envelope field raises rather than being coerced,
 * so a format change is loud at the first record instead of quietly producing facts with default
 * values that look real.
 */
object HubJournalCodec {
    fun parse(line: String): HubRecord {
        val json =
            Json.parseToJsonElement(line) as? JsonObject
                ?: throw IllegalArgumentException("hub journal line is not a JSON object")
        val fields = LinkedHashMap<String, BigDecimal?>()
        (json["fields"] as? JsonObject)?.forEach { (name, element) -> fields[name] = toDecimal(element) }
        return HubRecord(
            dataset = json.str("dataset"),
            scope = json.str("scope"),
            key = json.str("key"),
            revision = json.long("revision").toInt(),
            knownAt = json.long("known_at"),
            effectiveAt = json.long("effective_at"),
            availability = json.str("availability"),
            source = (json["source"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            seq = json.longOrNull("seq") ?: 0L,
            fields = fields,
            periodStart = json.longOrNull("period_start"),
            periodEnd = json.longOrNull("period_end"),
        )
    }

    /**
     * A field value as a number, or `null` when the hub recorded it as unknown.
     *
     * Numbers arrive as decimal STRINGS -- the hub's convention, and honoring it is what keeps a
     * value byte-identical on every machine. A boolean becomes 1 or 0 so the DSL can compare it
     * numerically; free text is not a strategy input and yields null, which the engine renders as
     * Undefined rather than as zero.
     */
    private fun toDecimal(element: JsonElement): BigDecimal? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        primitive.booleanOrNull?.let { return if (it) BigDecimal.ONE else BigDecimal.ZERO }
        val text = primitive.contentOrNull ?: return null
        return try {
            BigDecimal(text)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("hub journal record is missing string field '$key'")

    private fun JsonObject.long(key: String): Long =
        longOrNull(key) ?: throw IllegalArgumentException("hub journal record is missing numeric field '$key'")

    private fun JsonObject.longOrNull(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.longOrNull
    }
}
