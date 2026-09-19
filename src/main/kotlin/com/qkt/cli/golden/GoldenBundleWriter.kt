package com.qkt.cli.golden

import com.qkt.cli.daemon.StateDir
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the golden bundle zip: every engine audit file, the session's order journal and the MT5
 * transport journal clipped to the audit window, plus the manifest. The zip is built owner-only in
 * a temp sibling and moved into place, so a failed capture never leaves a partial bundle.
 */
internal fun writeGoldenBundle(
    output: Path,
    stateDir: StateDir,
    session: String,
    safeSession: String,
    auditFiles: List<Path>,
    audit: AuditSummary,
    transportFiles: List<Path>,
    transport: TransportSummary,
    createdAt: Instant,
    readOnly: Boolean,
) {
    val absolute = output.toAbsolutePath().normalize()
    absolute.parent?.let(Files::createDirectories)
    val temp = absolute.resolveSibling(".${absolute.fileName}.${UUID.randomUUID()}.tmp")
    val entries = mutableListOf<EntryEvidence>()
    try {
        ZipOutputStream(
            Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
        ).use { zip ->
            makePrivateFile(temp)
            for (file in auditFiles) {
                entries.add(
                    addJsonl(
                        zip,
                        file,
                        "engine/${file.fileName}",
                        Long.MIN_VALUE,
                        Long.MAX_VALUE,
                    ),
                )
            }
            val orderDir = stateDir.stateRoot.resolve("journal").resolve(safeSession)
            for (file in jsonlFiles(orderDir)) {
                entries.add(
                    addJsonl(zip, file, "orders/${file.fileName}", audit.firstTimestampMs, audit.lastTimestampMs),
                )
            }
            val transportRoot = stateDir.stateRoot.resolve("mt5-transport-journal")
            for (file in transportFiles) {
                val relative = transportRoot.relativize(file).joinToString("/")
                val evidence =
                    addJsonl(zip, file, "gateway/$relative", audit.firstTimestampMs, audit.lastTimestampMs)
                entries.add(evidence)
            }
            putText(
                zip,
                "manifest.json",
                renderCaptureManifest(session, audit, transport, createdAt, entries, readOnly),
            )
        }
        try {
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}

private fun addJsonl(
    zip: ZipOutputStream,
    file: Path,
    name: String,
    fromMs: Long,
    toMs: Long,
): EntryEvidence {
    val digest = MessageDigest.getInstance("SHA-256")
    var records = 0L
    // Retention may have gzipped the on-disk day-file; bundle entries stay plain JSONL.
    val entryName = name.removeSuffix(".gz")
    val entry = ZipEntry(entryName).apply { time = 0L }
    zip.putNextEntry(entry)
    journalReader(file).use { reader ->
        var lineNumber = 0L
        while (true) {
            val line = reader.readLine() ?: break
            lineNumber += 1L
            if (line.isBlank()) continue
            val record = parseRecord(file, lineNumber, line)
            if (timestamp(record, file, lineNumber) !in fromMs..toMs) continue
            val bytes = "$line\n".toByteArray(StandardCharsets.UTF_8)
            zip.write(bytes)
            digest.update(bytes)
            records += 1L
        }
    }
    zip.closeEntry()
    return EntryEvidence(entryName, records, digest.digest().toHex())
}

private fun putText(
    zip: ZipOutputStream,
    name: String,
    text: String,
) {
    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
    zip.write(text.toByteArray(StandardCharsets.UTF_8))
    zip.closeEntry()
}
