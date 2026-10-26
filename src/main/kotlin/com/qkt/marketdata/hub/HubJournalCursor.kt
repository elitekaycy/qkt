package com.qkt.marketdata.hub

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * An incremental reader over one dataset's journal: each [poll] returns only the complete lines
 * appended since the last one, across every day file, reading only the new bytes.
 *
 * A live session polls several times a second for as long as it runs, while the journal only
 * grows, so re-reading every file on every poll would make the poll's cost proportional to the
 * whole history. Keeping a byte offset per file keeps it proportional to what was appended.
 * Backfill appends to old day files, so every file is watched, not just the newest.
 *
 * The same two rules as [HubJournal] hold: an unterminated trailing line is left for the next
 * poll, and a malformed line is skipped and counted. A file that shrank (the writer repaired a
 * torn line) is re-read from its start; records already delivered are filtered by the caller's
 * sequence guard, so a repair can neither drop nor duplicate a fact.
 */
class HubJournalCursor(
    private val root: Path,
    private val dataset: String,
) {
    private val offsets = HashMap<Path, Long>()

    /** Lines this cursor could not parse. */
    var skipped: Long = 0L
        private set

    /** Lines appended since the previous call, in file order then line order. */
    fun poll(): List<HubRecord> {
        val dir = root.resolve("journal").resolve(dataset)
        if (!Files.isDirectory(dir)) return emptyList()
        val files =
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".ndjson") }.sorted().toList()
            }
        val out = ArrayList<HubRecord>()
        for (file in files) {
            val size = Files.size(file)
            var from = offsets[file] ?: 0L
            if (size < from) from = 0L
            if (size == from) continue
            val fresh = readFrom(file, from, size)
            val lastNewline = fresh.lastIndexOf('\n'.code.toByte())
            if (lastNewline < 0) continue
            offsets[file] = from + lastNewline + 1
            for (line in String(fresh, 0, lastNewline + 1, StandardCharsets.UTF_8).split('\n')) {
                if (line.isBlank()) continue
                try {
                    out.add(HubJournalCodec.parse(line))
                } catch (e: IllegalArgumentException) {
                    skipped++
                } catch (e: IndexOutOfBoundsException) {
                    skipped++
                }
            }
        }
        return out
    }

    private fun readFrom(
        file: Path,
        from: Long,
        to: Long,
    ): ByteArray =
        FileChannel.open(file).use { channel ->
            val buffer = ByteBuffer.allocate((to - from).toInt())
            var at = from
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, at)
                if (n < 0) break
                at += n
            }
            buffer.array().copyOf(buffer.position())
        }

    private fun ByteArray.lastIndexOf(target: Byte): Int {
        for (i in indices.reversed()) if (this[i] == target) return i
        return -1
    }
}
