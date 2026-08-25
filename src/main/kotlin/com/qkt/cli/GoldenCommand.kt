package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
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
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
            "materialize" -> materialize()
            else -> {
                System.err.println("qkt: unknown golden action '${action ?: ""}' (expected: capture or materialize)")
                printUsage()
                ExitCodes.ARG_ERROR
            }
        }

    private fun materialize(): Int =
        try {
            val bundle = Path.of(args.requireOption("bundle"))
            val output = Path.of(args.requireOption("out"))
            val summary = GoldenReplayDataMaterializer(bundle, output, clock).materialize()
            println(
                "qkt golden materialize: wrote ${output.toAbsolutePath().normalize()} " +
                    "(${summary.liveTicks} live ticks, ${summary.warmupTicks} warmup ticks, " +
                    "${summary.candles} candles)",
            )
            ExitCodes.SUCCESS
        } catch (error: Exception) {
            System.err.println("qkt: golden materialize failed: ${error.message}")
            ExitCodes.USER_ERROR
        }

    private fun capture(): Int {
        val session = args.requireOption("session").trim()
        val readOnly = args.flag("read-only")
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
            if (readOnly) {
                require(audit.fillCount == 0L) { "read-only session contains ${audit.fillCount} fill event(s)" }
            } else {
                require(audit.fillCount > 0L) { "session has no captured fills" }
            }
            assertNoDrops(auditDir, "audit", audit.firstTimestampMs, audit.lastTimestampMs)
            val transportRoot = stateDir.stateRoot.resolve("mt5-transport-journal")
            val transportFiles = jsonlFilesRecursive(transportRoot)
            val transport = scanTransport(transportFiles, audit)
            require(transport.exchangeCount > 0L) { "session window has no captured MT5 gateway exchanges" }
            if (readOnly) {
                require(transport.mutationCount == 0L) {
                    "read-only session contains ${transport.mutationCount} mutating gateway exchange(s)"
                }
            } else {
                require(transport.linkedPlacements > 0L) {
                    "session has no MT5 order exchange linked to a filled audit order"
                }
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
                readOnly = readOnly,
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
                    renderManifest(session, audit, transport, createdAt, entries, readOnly),
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
        var warmupTicks = 0L
        var candles = 0L
        var streamCandles = 0L
        var strategyCandleEvaluations = 0L
        var fills = 0L
        val filledOrderIds = mutableSetOf<String>()
        val filledBrokerOrderIds = mutableSetOf<String>()
        for (file in files) {
            journalReader(file).use { reader ->
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
                        "com.qkt.events.TickEvent" -> {
                            requireStructuredTick(record, file, lineNumber)
                            ticks += 1L
                        }
                        "com.qkt.events.WarmupTickEvent" -> {
                            requireStructuredTick(record, file, lineNumber)
                            warmupTicks += 1L
                        }
                        "com.qkt.events.CandleEvent" -> {
                            requireStructuredCandle(record, file, lineNumber)
                            candles += 1L
                        }
                        "com.qkt.events.StreamCandleEvent" -> {
                            requireText(record, "broker", file, lineNumber)
                            requireText(record, "timeframe", file, lineNumber)
                            requireStructuredCandle(record, file, lineNumber)
                            streamCandles += 1L
                        }
                        "com.qkt.events.StrategyCandleEvaluatedEvent" -> {
                            requireText(record, "strategyId", file, lineNumber)
                            requireText(record, "alias", file, lineNumber)
                            requireText(record, "broker", file, lineNumber)
                            requireText(record, "timeframe", file, lineNumber)
                            requireStructuredCandle(record, file, lineNumber)
                            strategyCandleEvaluations += 1L
                        }
                        "com.qkt.events.BrokerEvent.OrderFilled",
                        "com.qkt.events.BrokerEvent.OrderPartiallyFilled",
                        -> {
                            fills += 1L
                            record["orderId"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?.let(filledOrderIds::add)
                            (record["fill"] as? JsonObject)
                                ?.get("brokerOrderId")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?.let(filledBrokerOrderIds::add)
                        }
                    }
                }
            }
        }
        require(first != Long.MAX_VALUE) { "engine audit journal is empty" }
        return AuditSummary(
            first,
            last,
            ticks,
            warmupTicks,
            candles,
            streamCandles,
            strategyCandleEvaluations,
            fills,
            filledOrderIds,
            filledBrokerOrderIds,
        )
    }

    private fun requireStructuredTick(
        record: JsonObject,
        file: Path,
        lineNumber: Long,
    ) {
        requireText(record, "symbol", file, lineNumber)
        val tick =
            record["tick"] as? JsonObject
                ?: throw IllegalArgumentException("missing structured tick at $file:$lineNumber")
        requireLong(tick, "timestampMs", file, lineNumber)
        requireDecimal(tick, "price", file, lineNumber)
    }

    private fun requireStructuredCandle(
        record: JsonObject,
        file: Path,
        lineNumber: Long,
    ) {
        requireText(record, "symbol", file, lineNumber)
        val candle =
            record["candle"] as? JsonObject
                ?: throw IllegalArgumentException("missing structured candle at $file:$lineNumber")
        requireLong(candle, "startTimeMs", file, lineNumber)
        requireLong(candle, "endTimeMs", file, lineNumber)
        for (field in listOf("open", "high", "low", "close", "volume")) {
            requireDecimal(candle, field, file, lineNumber)
        }
    }

    private fun requireText(
        record: JsonObject,
        field: String,
        file: Path,
        lineNumber: Long,
    ): String =
        record[field]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("missing $field at $file:$lineNumber")

    private fun requireLong(
        record: JsonObject,
        field: String,
        file: Path,
        lineNumber: Long,
    ): Long =
        record[field]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
            ?: throw IllegalArgumentException("missing numeric $field at $file:$lineNumber")

    private fun requireDecimal(
        record: JsonObject,
        field: String,
        file: Path,
        lineNumber: Long,
    ): BigDecimal =
        record[field]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: throw IllegalArgumentException("missing decimal $field at $file:$lineNumber")

    private fun scanTransport(
        files: List<Path>,
        audit: AuditSummary,
    ): TransportSummary {
        var exchanges = 0L
        var linkedPlacements = 0L
        var mutations = 0L
        for (file in files) {
            journalReader(file).use { reader ->
                var lineNumber = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber += 1L
                    if (line.isBlank()) continue
                    val record = parseRecord(file, lineNumber, line)
                    if (timestamp(record, file, lineNumber) !in audit.firstTimestampMs..audit.lastTimestampMs) continue
                    exchanges += 1L
                    val endpoint = record["path"]?.jsonPrimitive?.contentOrNull?.substringBefore('?')
                    if (endpoint in MUTATING_ENDPOINTS) mutations += 1L
                    val idempotencyKey = record["idempotencyKey"]?.jsonPrimitive?.contentOrNull
                    val engineOrderId = record["engineOrderId"]?.jsonPrimitive?.contentOrNull
                    val brokerOrderId = responseBrokerOrderId(record)
                    val responseCode = record["responseCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    if (
                        record["method"]?.jsonPrimitive?.contentOrNull == "POST" &&
                        endpoint == "/order" &&
                        responseCode != null &&
                        responseCode in 200..299 &&
                        (
                            engineOrderId in audit.filledOrderIds ||
                                idempotencyKey in audit.filledOrderIds ||
                                brokerOrderId in audit.filledBrokerOrderIds
                        )
                    ) {
                        linkedPlacements += 1L
                    }
                }
            }
        }
        return TransportSummary(exchanges, linkedPlacements, mutations)
    }

    private fun responseBrokerOrderId(record: JsonObject): String? {
        val raw = record["responseBody"]?.jsonPrimitive?.contentOrNull ?: return null
        val response = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val result = response["result"] as? JsonObject ?: return null
        return result["order"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
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

    private fun renderManifest(
        session: String,
        audit: AuditSummary,
        transport: TransportSummary,
        createdAt: Instant,
        entries: List<EntryEvidence>,
        readOnly: Boolean,
    ): String =
        buildString {
            append("{\n")
            append("  \"schemaVersion\": 2,\n")
            append("  \"kind\": \"MT5_GOLDEN_CAPTURE\",\n")
            append("  \"captureMode\": \"").append(if (readOnly) "READ_ONLY" else "TRADING").append("\",\n")
            append("  \"session\": ").append(json(session)).append(",\n")
            append("  \"createdAtUtc\": ").append(json(createdAt.toString())).append(",\n")
            append("  \"captureQktVersion\": ").append(json(BuildInfo.VERSION)).append(",\n")
            append("  \"captureGitSha\": ").append(json(BuildInfo.GIT_SHA)).append(",\n")
            append("  \"captureBuildTimestamp\": ").append(json(BuildInfo.BUILD_TIMESTAMP)).append(",\n")
            append("  \"window\": {\"fromMs\": ").append(audit.firstTimestampMs)
            append(", \"toMs\": ").append(audit.lastTimestampMs).append("},\n")
            append("  \"counts\": {\"ticks\": ").append(audit.tickCount)
            append(", \"warmupTicks\": ").append(audit.warmupTickCount)
            append(", \"candles\": ").append(audit.candleCount)
            append(", \"streamCandles\": ").append(audit.streamCandleCount)
            append(", \"strategyCandleEvaluations\": ").append(audit.strategyCandleEvaluationCount)
            append(", \"fills\": ").append(audit.fillCount)
            append(", \"gatewayExchanges\": ").append(transport.exchangeCount)
            append(", \"linkedPlacements\": ").append(transport.linkedPlacements)
            append(", \"mutations\": ").append(transport.mutationCount).append("},\n")
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

    /**
     * Opens a journal day-file for line reading, transparently gunzipping `.jsonl.gz` files
     * produced by [com.qkt.observe.JournalRetention] compression.
     */
    private fun journalReader(file: Path): BufferedReader {
        val input = Files.newInputStream(file)
        val stream = if (file.fileName.toString().endsWith(".gz")) GZIPInputStream(input) else input
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
    }

    private fun isJournalFile(file: Path): Boolean {
        val name = file.fileName.toString()
        return name.endsWith(".jsonl") || name.endsWith(".jsonl.gz")
    }

    private fun jsonlFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { isJournalFile(it) }
                .sorted()
                .toList()
        }
    }

    private fun jsonlFilesRecursive(dir: Path): List<Path> {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.walk(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { isJournalFile(it) }
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
        System.err.println(
            "usage: qkt golden capture --session <strategy> [--state-dir <dir>] [--out <zip>] [--read-only]",
        )
        System.err.println("       qkt golden materialize --bundle <zip> --out <data-root>")
    }

    private data class AuditSummary(
        val firstTimestampMs: Long,
        val lastTimestampMs: Long,
        val tickCount: Long,
        val warmupTickCount: Long,
        val candleCount: Long,
        val streamCandleCount: Long,
        val strategyCandleEvaluationCount: Long,
        val fillCount: Long,
        val filledOrderIds: Set<String>,
        val filledBrokerOrderIds: Set<String>,
    )

    private data class TransportSummary(
        val exchangeCount: Long,
        val linkedPlacements: Long,
        val mutationCount: Long,
    )

    private data class EntryEvidence(
        val name: String,
        val records: Long,
        val sha256: String,
    )

    private companion object {
        val MUTATING_ENDPOINTS =
            setOf("/order", "/close_position", "/position_close_partial", "/modify_sl_tp", "/cancel_order")
    }
}
