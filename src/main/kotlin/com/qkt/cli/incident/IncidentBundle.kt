package com.qkt.cli.incident

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val COPY_BUFFER_SIZE = 8192

/**
 * The incident zip being written: adds size-capped files and filtered journal slices, and keeps
 * the ordered lists of included entries and warnings the manifest reports.
 */
internal class IncidentBundle(
    private val zip: ZipOutputStream,
    private val maxFileBytes: Long,
) {
    /** Entry names written so far, in write order. */
    val included = mutableListOf<String>()

    /** Collection warnings (missing sources, truncations), in the order they occurred. */
    val warnings = mutableListOf<String>()

    /** Records a warning for the manifest. */
    fun warn(message: String) {
        warnings.add(message)
    }

    /** Adds [path] as [entryName], keeping only its last `maxFileBytes` bytes when it is larger. */
    fun addFile(
        path: Path,
        entryName: String,
    ) {
        val size = Files.size(path)
        if (size > maxFileBytes) {
            warnings.add("truncated $entryName from $size to last $maxFileBytes bytes")
        }
        zip.putNextEntry(ZipEntry(entryName))
        Files.newInputStream(path).use { input ->
            if (size > maxFileBytes) input.skipNBytes(size - maxFileBytes)
            copyLimited(input, maxFileBytes)
        }
        zip.closeEntry()
        included.add(entryName)
    }

    /** Adds the lines of [file] that [keep] accepts as [entryName]; no entry is written when none match. */
    fun addMatchingLines(
        file: Path,
        entryName: String,
        keep: (String) -> Boolean,
    ) {
        var opened = false
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!keep(line)) continue
                if (!opened) {
                    zip.putNextEntry(ZipEntry(entryName))
                    opened = true
                }
                zip.write(line.toByteArray(StandardCharsets.UTF_8))
                zip.write('\n'.code)
            }
        }
        if (opened) {
            zip.closeEntry()
            included.add(entryName)
        }
    }

    /** Adds [text] as a UTF-8 entry without recording it in [included]. */
    fun putText(
        entryName: String,
        text: String,
    ) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun copyLimited(
        input: InputStream,
        maxBytes: Long,
    ) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = maxBytes
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            zip.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }
}
