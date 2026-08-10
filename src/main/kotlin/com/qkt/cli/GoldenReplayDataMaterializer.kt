package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DayRange
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.Manifest
import com.qkt.marketdata.store.ManifestStore
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class GoldenReplayDataSummary(
    val liveTicks: Long,
    val warmupTicks: Long,
    val candles: Long,
)

/** Converts verified golden market records into the normal QKT tick and bar stores. */
internal class GoldenReplayDataMaterializer(
    private val bundle: Path,
    private val output: Path,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = false }
    private val safeSegment = Regex("[A-Za-z0-9._-]+")

    fun materialize(): GoldenReplayDataSummary {
        require(Files.isRegularFile(bundle)) { "golden bundle not found: $bundle" }
        val absolute = output.toAbsolutePath().normalize()
        require(!Files.exists(absolute)) { "output already exists: $absolute" }
        val parent = absolute.parent ?: error("output has no parent: $absolute")
        Files.createDirectories(parent)
        val temp = Files.createTempDirectory(parent, ".${absolute.fileName}.")
        makePrivateDirectory(temp)
        try {
            val capture = readAndVerifyBundle()
            writeTickStore(temp, capture.ticks)
            writeBarStores(temp, capture.candles)
            writeReplayManifest(temp, capture)
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, absolute)
            }
            return GoldenReplayDataSummary(
                liveTicks = capture.ticks.count { !it.warmup }.toLong(),
                warmupTicks = capture.ticks.count { it.warmup }.toLong(),
                candles = capture.candles.size.toLong(),
            )
        } finally {
            if (Files.exists(temp)) deleteTree(temp)
        }
    }

    private fun readAndVerifyBundle(): Capture {
        ZipFile(bundle.toFile()).use { zip ->
            val manifestEntry =
                zip.getEntry("manifest.json")
                    ?: throw IllegalArgumentException("golden bundle has no manifest.json")
            val manifest =
                zip.getInputStream(manifestEntry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    parseObject(reader.readText(), "manifest.json", 1L)
                }
            require(manifest["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() == 2) {
                "unsupported golden schemaVersion"
            }
            require(manifest["kind"]?.jsonPrimitive?.contentOrNull == "MT5_GOLDEN_CAPTURE") {
                "unsupported golden bundle kind"
            }
            val entries =
                manifest["entries"]?.jsonArray
                    ?: throw IllegalArgumentException("golden bundle has no entries")
            val names = mutableSetOf<String>()
            val engineNames = mutableListOf<String>()
            for (element in entries) {
                val evidence = element.jsonObject
                val name = requireText(evidence, "path", "manifest.json", 1L)
                require(names.add(name)) { "duplicate golden entry: $name" }
                require(name.startsWith("engine/") || name.startsWith("orders/") || name.startsWith("gateway/")) {
                    "unsupported golden entry path: $name"
                }
                require(!name.startsWith('/') && name.split('/').none { it == ".." || it.isBlank() }) {
                    "unsafe golden entry path: $name"
                }
                val expectedRecords = requireLong(evidence, "records", "manifest.json", 1L)
                val expectedHash = requireText(evidence, "sha256", "manifest.json", 1L)
                val entry = zip.getEntry(name) ?: throw IllegalArgumentException("golden entry is missing: $name")
                require(!entry.isDirectory) { "golden entry is a directory: $name" }
                val actualHash = hashEntry(zip, name)
                require(actualHash == expectedHash) { "golden entry hash mismatch: $name" }
                val records = countRecords(zip, name)
                require(records == expectedRecords) {
                    "golden entry record count mismatch: $name expected=$expectedRecords actual=$records"
                }
                if (name.startsWith("engine/") && name.endsWith(".jsonl")) engineNames.add(name)
            }
            require(engineNames.isNotEmpty()) { "golden bundle has no engine JSONL" }
            return readMarketRecords(zip, engineNames.sorted(), manifest)
        }
    }

    private fun readMarketRecords(
        zip: ZipFile,
        engineNames: List<String>,
        manifest: JsonObject,
    ): Capture {
        val ticks = mutableListOf<RecordedTick>()
        val candles = mutableListOf<RecordedCandle>()
        val sequences = mutableSetOf<Long>()
        for (name in engineNames) {
            zip.getInputStream(zip.getEntry(name)).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                var lineNumber = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber += 1L
                    if (line.isBlank()) continue
                    val record = parseObject(line, name, lineNumber)
                    val sequence = requireLong(record, "seq", name, lineNumber)
                    require(sequences.add(sequence)) { "duplicate engine sequence $sequence in $name:$lineNumber" }
                    when (record["eventType"]?.jsonPrimitive?.contentOrNull) {
                        TICK_EVENT -> ticks.add(readTick(record, sequence, warmup = false, name, lineNumber))
                        WARMUP_TICK_EVENT -> ticks.add(readTick(record, sequence, warmup = true, name, lineNumber))
                        CANDLE_EVENT -> candles.add(readCandle(record, sequence, name, lineNumber))
                    }
                }
            }
        }
        val counts =
            manifest["counts"]?.jsonObject
                ?: throw IllegalArgumentException("golden bundle has no counts")
        require(ticks.count { !it.warmup }.toLong() == requireLong(counts, "ticks", "manifest.json", 1L)) {
            "golden live tick count does not match manifest"
        }
        require(ticks.count { it.warmup }.toLong() == requireLong(counts, "warmupTicks", "manifest.json", 1L)) {
            "golden warmup tick count does not match manifest"
        }
        require(candles.size.toLong() == requireLong(counts, "candles", "manifest.json", 1L)) {
            "golden candle count does not match manifest"
        }
        require(ticks.any { !it.warmup }) { "golden bundle has no live ticks" }
        return Capture(manifest, ticks.sortedWith(compareBy({ it.tick.timestamp }, { it.sequence })), candles)
    }

    private fun readTick(
        record: JsonObject,
        sequence: Long,
        warmup: Boolean,
        source: String,
        lineNumber: Long,
    ): RecordedTick {
        val symbol = requireQktSymbol(record, source, lineNumber)
        val tick =
            record["tick"] as? JsonObject
                ?: throw IllegalArgumentException("missing structured tick at $source:$lineNumber")
        val value =
            Tick(
                symbol = symbol,
                price = requireDecimal(tick, "price", source, lineNumber),
                timestamp = requireLong(tick, "timestampMs", source, lineNumber),
                volume = optionalDecimal(tick, "volume", source, lineNumber),
                bid = optionalDecimal(tick, "bid", source, lineNumber),
                ask = optionalDecimal(tick, "ask", source, lineNumber),
                bidVolume = optionalDecimal(tick, "bidVolume", source, lineNumber),
                askVolume = optionalDecimal(tick, "askVolume", source, lineNumber),
            )
        require(value.timestamp >= 0L && value.price.signum() > 0) { "invalid tick at $source:$lineNumber" }
        require(value.bid == null || value.ask == null || value.bid <= value.ask) {
            "crossed tick quote at $source:$lineNumber"
        }
        return RecordedTick(sequence, warmup, value)
    }

    private fun readCandle(
        record: JsonObject,
        sequence: Long,
        source: String,
        lineNumber: Long,
    ): RecordedCandle {
        val symbol = requireQktSymbol(record, source, lineNumber)
        val value =
            record["candle"] as? JsonObject
                ?: throw IllegalArgumentException("missing structured candle at $source:$lineNumber")
        val candle =
            Candle(
                symbol = symbol,
                open = requireDecimal(value, "open", source, lineNumber),
                high = requireDecimal(value, "high", source, lineNumber),
                low = requireDecimal(value, "low", source, lineNumber),
                close = requireDecimal(value, "close", source, lineNumber),
                volume = requireDecimal(value, "volume", source, lineNumber),
                startTime = requireLong(value, "startTimeMs", source, lineNumber),
                endTime = requireLong(value, "endTimeMs", source, lineNumber),
                bid = optionalDecimal(value, "bid", source, lineNumber),
                ask = optionalDecimal(value, "ask", source, lineNumber),
            )
        require(candle.startTime >= 0L && candle.endTime > candle.startTime) {
            "invalid candle window at $source:$lineNumber"
        }
        require(
            candle.low <= candle.open &&
                candle.low <= candle.close &&
                candle.high >= candle.open &&
                candle.high >= candle.close,
        ) {
            "invalid candle OHLC at $source:$lineNumber"
        }
        require(candle.volume.signum() >= 0) { "negative candle volume at $source:$lineNumber" }
        return RecordedCandle(sequence, candle)
    }

    private fun writeTickStore(
        root: Path,
        records: List<RecordedTick>,
    ) {
        val symbolsByBare = records.groupBy { it.tick.symbol.substringAfter(':') }
        for ((bare, symbolRecords) in symbolsByBare) {
            val qktSymbols = symbolRecords.map { it.tick.symbol }.toSet()
            require(qktSymbols.size == 1) { "multiple broker symbols share tick-store key '$bare': $qktSymbols" }
            val byDay = symbolRecords.groupBy { utcDay(it.tick.timestamp) }.toSortedMap()
            for ((day, dayRecords) in byDay) {
                val dir = root.resolve("symbols").resolve(bare)
                Files.createDirectories(dir)
                makePrivateDirectory(dir)
                val text =
                    buildString {
                        append("timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume\n")
                        for (record in dayRecords) {
                            val tick = record.tick
                            append(tick.timestamp).append(',').append(bare).append(',')
                            append(tick.price.toPlainString()).append(',')
                            append(tick.volume?.toPlainString().orEmpty()).append(',')
                            append(tick.bid?.toPlainString().orEmpty()).append(',')
                            append(tick.ask?.toPlainString().orEmpty()).append(',')
                            append(tick.bidVolume?.toPlainString().orEmpty()).append(',')
                            append(tick.askVolume?.toPlainString().orEmpty()).append('\n')
                        }
                    }
                val file = dir.resolve("$day.csv")
                Files.writeString(file, text, StandardCharsets.UTF_8)
                makePrivateFile(file)
            }
            val ranges = byDay.keys.map { DayRange(it.toString(), it.plusDays(1).toString()) }
            ManifestStore(root, clock).write(Manifest(symbol = bare, ranges = ranges))
        }
    }

    private fun writeBarStores(
        root: Path,
        records: List<RecordedCandle>,
    ) {
        val csv = LocalBarStore(root, clock)
        val binary = BinaryBarStore(root)
        val grouped =
            records.groupBy { record ->
                val candle = record.candle
                val (broker, bare) = splitSymbol(candle.symbol)
                val window = TimeWindow(candle.endTime - candle.startTime)
                require(window.durationMs % 1_000L == 0L) {
                    "unsupported sub-second candle window: ${window.durationMs}ms"
                }
                BarKey(broker, bare, window)
            }
        for ((key, keyRecords) in grouped) {
            val byDay = keyRecords.map { it.candle }.groupBy { utcDay(it.startTime) }.toSortedMap()
            for ((day, candles) in byDay) {
                csv.writeDay(key.broker, key.symbol, key.window.canonicalSpec(), day, candles)
                csv.recordDay(key.broker, key.symbol, key.window.canonicalSpec(), day)
                binary.writeDay(key.broker, key.symbol, key.window, day, candles)
            }
        }
    }

    private fun writeReplayManifest(
        root: Path,
        capture: Capture,
    ) {
        val liveTicks = capture.ticks.filterNot { it.warmup }
        val firstLiveMs = liveTicks.minOf { it.tick.timestamp }
        val lastLiveMs = liveTicks.maxOf { it.tick.timestamp }
        val containingStarts =
            capture.candles
                .map { it.candle }
                .filter { firstLiveMs >= it.startTime && firstLiveMs < it.endTime }
                .map { it.startTime }
        val fromMs = containingStarts.minOrNull() ?: firstLiveMs
        val toMs =
            maxOf(
                lastLiveMs + 1L,
                capture.candles.maxOfOrNull { it.candle.endTime } ?: lastLiveMs + 1L,
            )
        val symbols =
            capture.ticks
                .map { it.tick.symbol }
                .toSet()
                .sorted()
        val timeframes =
            capture.candles
                .map { TimeWindow(it.candle.endTime - it.candle.startTime).canonicalSpec() }
                .toSet()
                .sorted()
        val sourceManifest = capture.manifest
        val sourceSession = requireText(sourceManifest, "session", "manifest.json", 1L)
        val sourceCaptureGitSha = requireText(sourceManifest, "captureGitSha", "manifest.json", 1L)
        val materializedAt = Instant.ofEpochMilli(clock.now()).toString()
        val text =
            buildString {
                append("{\n")
                append("  \"schema\": \"qkt-golden-replay-data-v1\",\n")
                append("  \"schemaVersion\": 1,\n")
                append("  \"sourceBundleSha256\": ").append(jsonString(sha256(bundle))).append(",\n")
                append("  \"sourceSession\": ").append(jsonString(sourceSession)).append(",\n")
                append("  \"sourceCaptureGitSha\": ").append(jsonString(sourceCaptureGitSha)).append(",\n")
                append("  \"materializerGitSha\": ").append(jsonString(BuildInfo.GIT_SHA)).append(",\n")
                append("  \"materializedAtUtc\": ").append(jsonString(materializedAt)).append(",\n")
                append("  \"replayWindow\": {\"fromMs\": ").append(fromMs)
                append(", \"toMs\": ").append(toMs)
                append(", \"fromUtc\": ").append(jsonString(Instant.ofEpochMilli(fromMs).toString()))
                append(", \"toUtc\": ").append(jsonString(Instant.ofEpochMilli(toMs).toString())).append("},\n")
                append("  \"counts\": {\"ticks\": ").append(liveTicks.size)
                append(", \"warmupTicks\": ").append(capture.ticks.size - liveTicks.size)
                append(", \"candles\": ").append(capture.candles.size).append("},\n")
                append("  \"symbols\": [").append(symbols.joinToString(",") { jsonString(it) }).append("],\n")
                append("  \"timeframes\": [").append(timeframes.joinToString(",") { jsonString(it) }).append("]\n")
                append("}\n")
            }
        val file = root.resolve("golden-replay-manifest.json")
        Files.writeString(file, text, StandardCharsets.UTF_8)
        makePrivateFile(file)
    }

    private fun requireQktSymbol(
        record: JsonObject,
        source: String,
        lineNumber: Long,
    ): String {
        val symbol = requireText(record, "symbol", source, lineNumber)
        splitSymbol(symbol)
        return symbol
    }

    private fun splitSymbol(symbol: String): Pair<String, String> {
        val parts = symbol.split(':', limit = 2)
        require(parts.size == 2 && parts.all { safeSegment.matches(it) }) { "unsafe or unqualified symbol: $symbol" }
        return parts[0] to parts[1]
    }

    private fun parseObject(
        text: String,
        source: String,
        lineNumber: Long,
    ): JsonObject =
        try {
            json.parseToJsonElement(text).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("malformed JSON at $source:$lineNumber: ${error.message}")
        }

    private fun requireText(
        record: JsonObject,
        field: String,
        source: String,
        lineNumber: Long,
    ): String =
        record[field]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("missing $field at $source:$lineNumber")

    private fun requireLong(
        record: JsonObject,
        field: String,
        source: String,
        lineNumber: Long,
    ): Long =
        record[field]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
            ?: throw IllegalArgumentException("missing numeric $field at $source:$lineNumber")

    private fun requireDecimal(
        record: JsonObject,
        field: String,
        source: String,
        lineNumber: Long,
    ): BigDecimal =
        optionalDecimal(record, field, source, lineNumber)
            ?: throw IllegalArgumentException("missing decimal $field at $source:$lineNumber")

    private fun optionalDecimal(
        record: JsonObject,
        field: String,
        source: String,
        lineNumber: Long,
    ): BigDecimal? {
        val raw = record[field]?.jsonPrimitive?.contentOrNull ?: return null
        return runCatching { BigDecimal(raw) }.getOrNull()
            ?: throw IllegalArgumentException("invalid decimal $field at $source:$lineNumber")
    }

    private fun hashEntry(
        zip: ZipFile,
        name: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(zip.getInputStream(zip.getEntry(name)), digest).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (input.read(buffer) >= 0) Unit
        }
        return digest.digest().toHex()
    }

    private fun countRecords(
        zip: ZipFile,
        name: String,
    ): Long =
        zip.getInputStream(zip.getEntry(name)).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.count { it.isNotBlank() }.toLong()
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(path), digest).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (input.read(buffer) >= 0) Unit
        }
        return digest.digest().toHex()
    }

    private fun jsonString(value: String): String =
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

    private fun utcDay(timestampMs: Long): LocalDate =
        Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC).toLocalDate()

    private fun makePrivateDirectory(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
    }

    private fun makePrivateFile(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private fun deleteTree(path: Path) {
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class RecordedTick(
        val sequence: Long,
        val warmup: Boolean,
        val tick: Tick,
    )

    private data class RecordedCandle(
        val sequence: Long,
        val candle: Candle,
    )

    private data class Capture(
        val manifest: JsonObject,
        val ticks: List<RecordedTick>,
        val candles: List<RecordedCandle>,
    )

    private data class BarKey(
        val broker: String,
        val symbol: String,
        val window: TimeWindow,
    )

    private companion object {
        const val TICK_EVENT = "com.qkt.events.TickEvent"
        const val WARMUP_TICK_EVENT = "com.qkt.events.WarmupTickEvent"
        const val CANDLE_EVENT = "com.qkt.events.CandleEvent"
    }
}
