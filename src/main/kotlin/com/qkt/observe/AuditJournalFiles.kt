package com.qkt.observe

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate

/**
 * One owner's engine-audit files under [rootDir]: an append-only, DSYNC'd JSONL file per UTC
 * day, rolled when the day changes, plus a per-day drop marker. Directories and files are
 * owner-only. Used only from the journal's worker thread.
 */
internal class AuditJournalFiles(
    private val rootDir: Path,
    private val owner: String,
) {
    private var day: LocalDate? = null
    private var channel: FileChannel? = null

    /** Append [bytes] to [eventDay]'s JSONL file, opening or rolling the file as needed. */
    fun append(
        eventDay: LocalDate,
        bytes: ByteArray,
    ) {
        val buffer = ByteBuffer.wrap(bytes)
        val output = channelFor(eventDay)
        while (buffer.hasRemaining()) output.write(buffer)
    }

    /** Close the open day file, if any; the next [append] reopens it. */
    fun close() {
        runCatching { channel?.close() }
        channel = null
        day = null
    }

    /** Overwrite [eventDay]'s drop marker with the cumulative dropped-event [count]. */
    fun writeDropMarker(
        eventDay: LocalDate,
        count: Long,
    ) {
        val dir = ownerDir()
        createPrivateDirectory(dir)
        val marker = dir.resolve("audit-$eventDay.dropped")
        Files.writeString(
            marker,
            "$count\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.DSYNC,
        )
        makeOwnerOnly(marker)
    }

    private fun channelFor(eventDay: LocalDate): FileChannel {
        val open = channel
        if (open != null && day == eventDay) return open
        runCatching { open?.close() }
        val dir = ownerDir()
        createPrivateDirectory(dir)
        val path = dir.resolve("audit-$eventDay.jsonl")
        val next =
            FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC,
            )
        makeOwnerOnly(path)
        channel = next
        day = eventDay
        return next
    }

    /**
     * The highest `seq` in this owner's most recent day file that holds one, or null when the
     * owner has no journaled events yet. The whole file is scanned, not just its tail, because a
     * file written before sequences resumed across restarts can hold a lower run after a higher one.
     */
    fun lastSequence(): Long? {
        val dir = ownerDir()
        if (!Files.isDirectory(dir)) return null
        val days =
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().matches(DAY_FILE) }.sorted(Comparator.reverseOrder()).toList()
            }
        return days.firstNotNullOfOrNull(::maxSequence)
    }

    private fun maxSequence(file: Path): Long? =
        Files.newBufferedReader(file, Charsets.UTF_8).useLines { lines ->
            lines
                .mapNotNull {
                    SEQ
                        .find(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                }.maxOrNull()
        }

    private fun ownerDir(): Path = rootDir.resolve(owner.ifBlank { "_session" }.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private companion object {
        val DAY_FILE = Regex("""audit-\d{4}-\d{2}-\d{2}\.jsonl""")
        val SEQ = Regex(""""seq":(\d+)""")
    }

    private fun createPrivateDirectory(path: Path) {
        Files.createDirectories(path)
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
    }

    private fun makeOwnerOnly(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }
}
