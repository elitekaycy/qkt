package com.qkt.observe

import com.qkt.common.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * Append-only order-event journal: one JSONL file per strategy per UTC day,
 * `<root>/<strategy>/journal-YYYY-MM-DD.jsonl`. Never truncated, never rewritten —
 * when real money misbehaves, the first question is "what exactly happened, in
 * order?", and the bounded event ring cannot answer it (FIA §2.4).
 *
 * Writes append with DSYNC so a crash cannot lose acknowledged lines. A write
 * failure logs at ERROR and keeps trading — the journal is an audit control, not a
 * trading dependency.
 */
class OrderJournal(
    private val rootDir: Path,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OrderJournal::class.java)

    fun append(
        strategyId: String,
        kind: String,
        fields: Map<String, String?>,
    ) {
        val now = clock.now()
        val day =
            Instant
                .ofEpochMilli(now)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
        val dir = rootDir.resolve(strategyId.ifBlank { "_unattributed" })
        val line = StringBuilder()
        line.append("{\"ts\":").append(now)
        line.append(",\"kind\":\"").append(kind).append('"')
        for ((k, v) in fields) {
            if (v == null) continue
            line
                .append(",\"")
                .append(k)
                .append("\":\"")
                .append(v.replace("\"", "'"))
                .append('"')
        }
        line.append("}\n")
        try {
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve("journal-$day.jsonl"),
                line.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC,
            )
        } catch (e: Exception) {
            log.error("order journal append FAILED for $strategyId/$kind: ${e.message}")
        }
    }
}
