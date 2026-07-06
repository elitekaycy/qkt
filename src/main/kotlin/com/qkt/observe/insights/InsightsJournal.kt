package com.qkt.observe.insights

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * One durable insights envelope row waiting for collector acknowledgement.
 */
data class InsightsJournalEntry(
    /** Monotonic local sequence assigned by the qkt process journal. */
    val journalSeq: Long,
    /** Serialized wire envelope JSON for replay to qkt-insights. */
    val eventJson: String,
)

/**
 * Append-only local spool for insights envelopes. The file stores the final wire
 * envelope JSON, one row per line, prefixed by a monotonic journal sequence:
 *
 * `<journalSeq>\t<envelope-json>`
 *
 * A separate cursor file records the highest sequence acknowledged by the
 * collector. Replaying unacked rows may duplicate events after a crash between
 * POST success and cursor write, which is acceptable because qkt-insights
 * inserts are idempotent by `(instanceId, id)`.
 */
class InsightsJournal(
    dir: Path,
    instanceId: String,
) {
    private val journalPath: Path
    private val cursorPath: Path
    private var nextSeq: Long
    private var ackedSeq: Long

    init {
        Files.createDirectories(dir)
        val name = instanceId.ifBlank { "qkt" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        journalPath = dir.resolve("$name.jsonl")
        cursorPath = dir.resolve("$name.cursor")
        ackedSeq = readCursor()
        nextSeq = maxOf(readLastSeq(), ackedSeq) + 1L
    }

    /** Persist serialized envelopes and return their assigned journal sequences. */
    @Synchronized
    fun append(eventJsons: List<String>): List<InsightsJournalEntry> {
        if (eventJsons.isEmpty()) return emptyList()
        val entries =
            eventJsons.map { json ->
                InsightsJournalEntry(nextSeq++, json)
            }
        val bytes =
            entries
                .joinToString(separator = "") { "${it.journalSeq}\t${it.eventJson}\n" }
                .toByteArray(StandardCharsets.UTF_8)
        FileChannel
            .open(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            ).use { ch ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) ch.write(buffer)
                ch.force(true)
            }
        return entries
    }

    /** Return up to [limit] unacknowledged entries in journal order. */
    @Synchronized
    fun pending(limit: Int): List<InsightsJournalEntry> {
        if (!Files.exists(journalPath)) return emptyList()
        val out = ArrayList<InsightsJournalEntry>(limit)
        Files.newBufferedReader(journalPath, StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (out.size >= limit) break
                val entry = parseLine(line) ?: continue
                if (entry.journalSeq > ackedSeq) out.add(entry)
            }
        }
        return out
    }

    /** Return whether any unacknowledged entries remain in the journal. */
    @Synchronized
    fun hasPending(): Boolean = pending(1).isNotEmpty()

    /** Count unacknowledged entries without materializing their payloads. */
    @Synchronized
    fun pendingCount(): Long {
        if (!Files.exists(journalPath)) return 0L
        var count = 0L
        Files.newBufferedReader(journalPath, StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val entry = parseLine(line) ?: continue
                if (entry.journalSeq > ackedSeq) count++
            }
        }
        return count
    }

    /** Mark entries up to and including [journalSeq] as accepted by qkt-insights. */
    @Synchronized
    fun ack(journalSeq: Long) {
        if (journalSeq <= ackedSeq) return
        ackedSeq = journalSeq
        atomicWrite(cursorPath, "$ackedSeq\n")
        compactIfFullyAcked()
    }

    private fun compactIfFullyAcked() {
        if (pending(1).isNotEmpty()) return
        FileChannel
            .open(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { ch -> ch.force(true) }
    }

    private fun readCursor(): Long {
        if (!Files.exists(cursorPath)) return 0L
        return runCatching { Files.readString(cursorPath).trim().toLong() }.getOrDefault(0L)
    }

    private fun readLastSeq(): Long {
        if (!Files.exists(journalPath)) return 0L
        var last = 0L
        Files.newBufferedReader(journalPath, StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val seq = parseLine(line)?.journalSeq ?: continue
                if (seq > last) last = seq
            }
        }
        return last
    }

    private fun parseLine(line: String): InsightsJournalEntry? {
        val tab = line.indexOf('\t')
        if (tab <= 0 || tab == line.lastIndex) return null
        val seq = line.substring(0, tab).toLongOrNull() ?: return null
        return InsightsJournalEntry(seq, line.substring(tab + 1))
    }

    private fun atomicWrite(
        path: Path,
        text: String,
    ) {
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, text, StandardCharsets.UTF_8)
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
