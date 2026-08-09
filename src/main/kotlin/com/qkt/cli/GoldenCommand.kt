package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exports an authentic daemon session into a compressed, checksummed golden-evidence bundle. */
class GoldenCommand(
    private val args: Args,
    private val clock: Clock = SystemClock(),
) {
    /** Execute the requested golden-evidence action and return a process exit code. */
    fun run(): Int =
        when (val action = args.positional(0)) {
            "capture" -> capture()
            else -> {
                System.err.println("qkt: unknown golden action '${action ?: ""}' (expected: capture)")
                printUsage()
                ExitCodes.ARG_ERROR
            }
        }

    private fun capture(): Int {
        val session = args.requireOption("session").trim()
        if (session.isEmpty()) throw ArgError("--session must not be blank")
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val safeSession = sanitize(session)
        val auditDir = stateDir.stateRoot.resolve("audit-journal").resolve(safeSession)
        val auditFiles = jsonlFiles(auditDir)
        if (auditFiles.isEmpty()) {
            System.err.println("qkt: no engine audit journal found for session '$session'")
            return ExitCodes.USER_ERROR
        }

        return try {
            val audit = scanAudit(auditFiles)
            require(audit.tickCount > 0L) { "session has no captured inbound ticks" }
            require(audit.fillCount > 0L) { "session has no captured fills" }
            assertNoDrops(auditDir, "audit", audit.firstTimestampMs, audit.lastTimestampMs)
            val transportRoot = stateDir.stateRoot.resolve("mt5-transport-journal")
            val transportFiles = jsonlFilesRecursive(transportRoot)
            val transport = scanTransport(transportFiles, audit)
            require(transport.exchangeCount > 0L) { "session window has no captured MT5 gateway exchanges" }
            require(transport.linkedPlacements > 0L) {
                "session has no MT5 order exchange linked to a filled audit order"
            }
            assertNoDrops(transportRoot, "transport", audit.firstTimestampMs, audit.lastTimestampMs)

            val createdAt = Instant.ofEpochMilli(clock.now())
            val output =
                args.option("out")?.let(Path::of)
                    ?: Path.of("qkt-golden-$safeSession-${createdAt.toString().replace(Regex("[-:]"), "")}.zip")
            writeBundle(
                output = output,
                stateDir = stateDir,
                session = session,
                safeSession = safeSession,
                auditFiles = auditFiles,
                audit = audit,
                transportFiles = transportFiles,
                transport = transport,
                createdAt = createdAt,
            )
            println("qkt golden capture: wrote ${output.toAbsolutePath().normalize()}")
            ExitCodes.SUCCESS
        } catch (error: Exception) {
            System.err.println("qkt: golden capture failed: ${error.message}")
            ExitCodes.USER_ERROR
        }
    }

    private fun writeBundle(
        output: Path,
        stateDir: StateDir,
        session: String,
        safeSession: String,
        auditFiles: List<Path>,
        audit: AuditSummary,
        transportFiles: List<Path>,
        transport: TransportSummary,
        createdAt: Instant,
    ) {
        val absolute = output.toAbsolutePath().normalize()
        absolute.parent?.let(Files::createDirectories)
        val temp = absolute.resolveSibling(".${absolute.fileName}.${UUID.randomUUID()}.tmp")
        val entries = mutableListOf<EntryEvidence>()
        try {
            ZipOutputStream(
                Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            ).use { zip ->
                makeOwnerOnly(temp)
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
                    renderManifest(session, audit, transport, createdAt, entries),
                )
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun scanAudit(files: List<Path>): AuditSummary {
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var ticks = 0L
        var fills = 0L
        val filledOrderIds = mutableSetOf<String>()
        for (file in files) {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                var lineNumber = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber += 1L
                    if (line.isBlank()) continue
                    val record = parseRecord(file, lineNumber, line)
                    val timestamp = timestamp(record, file, lineNumber)
                    first = minOf(first, timestamp)
                    last = maxOf(last, timestamp)
                    when (record["eventType"]?.jsonPrimitive?.contentOrNull) {
                        "com.qkt.events.TickEvent" -> ticks += 1L
                        "com.qkt.events.BrokerEvent.OrderFilled",
                        "com.qkt.events.BrokerEvent.OrderPartiallyFilled",
                        -> {
                            fills += 1L
                            record["orderId"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?.let(filledOrderIds::add)
                        }
                    }
                }
            }
        }
        require(first != Long.MAX_VALUE) { "engine audit journal is empty" }
        return AuditSummary(first, last, ticks, fills, filledOrderIds)
    }

    private fun scanTransport(
        files: List<Path>,
        audit: AuditSummary,
    ): TransportSummary {
        var exchanges = 0L
        var linkedPlacements = 0L
        for (file in files) {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                var lineNumber = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber += 1L
                    if (line.isBlank()) continue
                    val record = parseRecord(file, lineNumber, line)
                    if (timestamp(record, file, lineNumber) !in audit.firstTimestampMs..audit.lastTimestampMs) continue
                    exchanges += 1L
                    val endpoint = record["path"]?.jsonPrimitive?.contentOrNull?.substringBefore('?')
                    val idempotencyKey = record["idempotencyKey"]?.jsonPrimitive?.contentOrNull
                    if (
                        record["method"]?.jsonPrimitive?.contentOrNull == "POST" &&
                        endpoint == "/order" &&
                        idempotencyKey in audit.filledOrderIds
                    ) {
                        linkedPlacements += 1L
                    }
                }
            }
        }
        return TransportSummary(exchanges, linkedPlacements)
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
        val entry = ZipEntry(name).apply { time = 0L }
        zip.putNextEntry(entry)
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
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
        return EntryEvidence(name, records, digest.digest().toHex())
    }

    private fun renderManifest(
        session: String,
        audit: AuditSummary,
        transport: TransportSummary,
        createdAt: Instant,
        entries: List<EntryEvidence>,
    ): String =
        buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"kind\": \"MT5_GOLDEN_CAPTURE\",\n")
            append("  \"session\": ").append(json(session)).append(",\n")
            append("  \"createdAtUtc\": ").append(json(createdAt.toString())).append(",\n")
            append("  \"qktVersion\": ").append(json(BuildInfo.VERSION)).append(",\n")
            append("  \"gitSha\": ").append(json(BuildInfo.GIT_SHA)).append(",\n")
            append("  \"window\": {\"fromMs\": ").append(audit.firstTimestampMs)
            append(", \"toMs\": ").append(audit.lastTimestampMs).append("},\n")
            append("  \"counts\": {\"ticks\": ").append(audit.tickCount)
            append(", \"fills\": ").append(audit.fillCount)
            append(", \"gatewayExchanges\": ").append(transport.exchangeCount)
            append(", \"linkedPlacements\": ").append(transport.linkedPlacements).append("},\n")
            append("  \"entries\": [\n")
            entries.sortedBy { it.name }.forEachIndexed { index, evidence ->
                append("    {\"path\": ").append(json(evidence.name))
                append(", \"records\": ").append(evidence.records)
                append(", \"sha256\": ").append(json(evidence.sha256)).append('}')
                if (index != entries.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }

    private fun parseRecord(
        file: Path,
        lineNumber: Long,
        line: String,
    ) = try {
        Json.parseToJsonElement(line).jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("malformed JSONL at $file:$lineNumber: ${error.message}")
    }

    private fun timestamp(
        record: kotlinx.serialization.json.JsonObject,
        file: Path,
        lineNumber: Long,
    ): Long =
        record["ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw IllegalArgumentException("missing numeric ts at $file:$lineNumber")

    private fun jsonlFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { it.fileName.toString().endsWith(".jsonl") }
                .sorted()
                .toList()
        }
    }

    private fun jsonlFilesRecursive(dir: Path): List<Path> {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.walk(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { it.fileName.toString().endsWith(".jsonl") }
                .sorted()
                .toList()
        }
    }

    private fun assertNoDrops(
        root: Path,
        prefix: String,
        fromMs: Long,
        toMs: Long,
    ) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        val fromDay = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).toLocalDate()
        val toDay = Instant.ofEpochMilli(toMs).atZone(ZoneOffset.UTC).toLocalDate()
        val markerPattern = Regex("${Regex.escape(prefix)}-(\\d{4}-\\d{2}-\\d{2})\\.dropped")
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .forEach { marker ->
                    val match = markerPattern.matchEntire(marker.fileName.toString()) ?: return@forEach
                    val day = LocalDate.parse(match.groupValues[1])
                    if (day.isBefore(fromDay) || day.isAfter(toDay)) return@forEach
                    val count =
                        Files.readString(marker).trim().toLongOrNull()
                            ?: throw IllegalArgumentException("invalid journal drop marker: $marker")
                    require(count == 0L) { "$prefix journal dropped $count record(s) on $day" }
                }
        }
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

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun json(value: String): String =
        buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun makeOwnerOnly(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private fun printUsage() {
        System.err.println("usage: qkt golden capture --session <strategy> [--state-dir <dir>] [--out <zip>]")
    }

    private data class AuditSummary(
        val firstTimestampMs: Long,
        val lastTimestampMs: Long,
        val tickCount: Long,
        val fillCount: Long,
        val filledOrderIds: Set<String>,
    )

    private data class TransportSummary(
        val exchangeCount: Long,
        val linkedPlacements: Long,
    )

    private data class EntryEvidence(
        val name: String,
        val records: Long,
        val sha256: String,
    )
}
