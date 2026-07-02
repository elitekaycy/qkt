package com.qkt.observe

import com.qkt.common.Clock
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * Append-only order-event journal: one JSONL file per strategy per UTC day,
 * `<root>/<strategy>/journal-YYYY-MM-DD.jsonl`. Never truncated, never rewritten —
 * when real money misbehaves, the first question is "what exactly happened, in
 * order?", and the bounded event ring cannot answer it (FIA §2.4).
 *
 * Each (strategy, UTC day) keeps a held-open [FileChannel] opened with DSYNC, so an
 * append is a single durable write — not an open/fsync/close per line, which stalled
 * the engine thread inside bus dispatch (#648). The channel rolls at the UTC day
 * boundary and everything closes via [close]. A write failure logs at ERROR, drops
 * the channel (the next append reopens fresh), and keeps trading — the journal is an
 * audit control, not a trading dependency. Appends after [close] fall back to a
 * one-shot durable write so late teardown events are never lost.
 */
class OrderJournal(
    private val rootDir: Path,
    private val clock: Clock,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(OrderJournal::class.java)

    private class DayChannel(
        val day: LocalDate,
        val channel: FileChannel,
    )

    private val channels = HashMap<String, DayChannel>()
    private var closed = false

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
        val strategyDir = strategyId.ifBlank { "_unattributed" }
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
        val bytes = line.toString().toByteArray(StandardCharsets.UTF_8)
        synchronized(this) {
            try {
                if (closed) {
                    // Teardown stragglers (e.g. a stop-time notification failure) still
                    // land durably; the channel cache is gone, so pay the one-shot cost.
                    val dir = rootDir.resolve(strategyDir)
                    Files.createDirectories(dir)
                    Files.write(
                        dir.resolve("journal-$day.jsonl"),
                        bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.DSYNC,
                    )
                    return
                }
                channelFor(strategyDir, day).write(ByteBuffer.wrap(bytes))
            } catch (e: Exception) {
                log.error("order journal append FAILED for $strategyId/$kind: ${e.message}")
                // Drop the cached channel so the next append reopens instead of
                // failing forever on a stale handle.
                channels.remove(strategyDir)?.let { stale -> runCatching { stale.channel.close() } }
            }
        }
    }

    /** Closes every held-open channel; later appends degrade to one-shot durable writes. */
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            for (open in channels.values) {
                runCatching { open.channel.close() }
            }
            channels.clear()
        }
    }

    private fun channelFor(
        strategyDir: String,
        day: LocalDate,
    ): FileChannel {
        val cached = channels[strategyDir]
        if (cached != null && cached.day == day) return cached.channel
        cached?.let { runCatching { it.channel.close() } }
        val dir = rootDir.resolve(strategyDir)
        Files.createDirectories(dir)
        val channel =
            FileChannel.open(
                dir.resolve("journal-$day.jsonl"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC,
            )
        channels[strategyDir] = DayChannel(day, channel)
        return channel
    }
}
