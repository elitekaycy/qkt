package com.qkt.cli

import com.qkt.candles.CandleAggregator
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
            val replayCandles = replayCandles(capture)
            writeTickStore(temp, capture.ticks)
            writeBarStores(temp, replayCandles)
            writeReplayManifest(temp, capture, replayCandles)
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
        val streamCandles = mutableListOf<RecordedCandle>()
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
                        STREAM_CANDLE_EVENT ->
                            streamCandles.add(readStreamCandle(record, sequence, name, lineNumber))
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
        val expectedStreamCandles = optionalLong(counts, "streamCandles", "manifest.json", 1L) ?: 0L
        require(streamCandles.size.toLong() == expectedStreamCandles) {
            "golden stream candle count does not match manifest"
        }
        require(ticks.any { !it.warmup }) { "golden bundle has no live ticks" }
        return Capture(
            manifest,
            ticks.sortedWith(compareBy({ it.tick.timestamp }, { it.sequence })),
            candles,
            streamCandles,
        )
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
        val sourceTimeframeMs =
            if (warmup) optionalLong(record, "sourceTimeframeMs", source, lineNumber) else null
        require(sourceTimeframeMs == null || sourceTimeframeMs > 0L) {
            "invalid warmup sourceTimeframeMs at $source:$lineNumber"
        }
        return RecordedTick(sequence, warmup, sourceTimeframeMs, value)
    }

    private fun readStreamCandle(
        record: JsonObject,
        sequence: Long,
        source: String,
        lineNumber: Long,
    ): RecordedCandle {
        val broker = requireText(record, "broker", source, lineNumber)
        val timeframe = requireText(record, "timeframe", source, lineNumber)
        val recorded =
            readCandle(
                record,
                sequence,
                source,
                lineNumber,
                provenance = CAPTURED_STREAM_CANDLE_EVENT,
            )
        val symbolBroker = splitSymbol(recorded.candle.symbol).first
        require(symbolBroker == broker) { "stream candle broker mismatch at $source:$lineNumber" }
        val window = TimeWindow.parse(timeframe)
        require(recorded.candle.endTime - recorded.candle.startTime == window.durationMs) {
            "stream candle timeframe mismatch at $source:$lineNumber"
        }
        return recorded
    }

    private fun readCandle(
        record: JsonObject,
        sequence: Long,
        source: String,
        lineNumber: Long,
        provenance: String = CAPTURED_CANDLE_EVENT,
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
        return RecordedCandle(sequence, candle, provenance)
    }

    private fun replayCandles(capture: Capture): List<RecordedCandle> {
        val merged = linkedMapOf<BarIdentity, RecordedCandle>()
        for (record in capture.candles + capture.streamCandles + rehydrateWarmupCandles(capture.ticks)) {
            val candle = record.candle
            val identity = BarIdentity(candle.symbol, candle.startTime, candle.endTime)
            val existing = merged[identity]
            if (existing == null) {
                merged[identity] = record
                continue
            }
            require(sameCandle(existing.candle, candle)) {
                "conflicting golden candles for $identity"
            }
            if (provenancePriority(record.provenance) > provenancePriority(existing.provenance)) {
                merged[identity] = record
            }
        }
        return merged.values.sortedWith(compareBy({ it.candle.startTime }, { it.sequence }))
    }

    private fun rehydrateWarmupCandles(records: List<RecordedTick>): List<RecordedCandle> =
        records
            .filter { it.warmup && it.sourceTimeframeMs != null }
            .groupBy { it.tick.symbol to checkNotNull(it.sourceTimeframeMs) }
            .flatMap { (_, streamRecords) ->
                val timeframeMs = checkNotNull(streamRecords.first().sourceTimeframeMs)
                require(timeframeMs % 1_000L == 0L) { "unsupported sub-second warmup timeframe: ${timeframeMs}ms" }
                val emitted = mutableListOf<RecordedCandle>()
                val sequence = streamRecords.minOf { it.sequence }
                val aggregator =
                    CandleAggregator.standalone(TimeWindow(timeframeMs)) { candle ->
                        emitted.add(RecordedCandle(sequence, candle, REHYDRATED_WARMUP_TICKS))
                    }
                val sorted = streamRecords.sortedWith(compareBy({ it.tick.timestamp }, { it.sequence }))
                for (record in sorted) aggregator.onTick(record.tick)
                val lastTick = sorted.last().tick.timestamp
                aggregator.flushClosed(lastTick + timeframeMs)
                emitted
            }

    private fun sameCandle(
        left: Candle,
        right: Candle,
    ): Boolean =
        left.symbol == right.symbol &&
            left.startTime == right.startTime &&
            left.endTime == right.endTime &&
            left.open.compareTo(right.open) == 0 &&
            left.high.compareTo(right.high) == 0 &&
            left.low.compareTo(right.low) == 0 &&
            left.close.compareTo(right.close) == 0 &&
            left.volume.compareTo(right.volume) == 0 &&
            nullableDecimalEquals(left.bid, right.bid) &&
            nullableDecimalEquals(left.ask, right.ask)

    private fun nullableDecimalEquals(
        left: BigDecimal?,
        right: BigDecimal?,
    ): Boolean = left == null && right == null || left != null && right != null && left.compareTo(right) == 0

    private fun provenancePriority(provenance: String): Int =
        when (provenance) {
            CAPTURED_STREAM_CANDLE_EVENT -> 3
            CAPTURED_CANDLE_EVENT -> 2
            REHYDRATED_WARMUP_TICKS -> 1
            else -> 0
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
        replayCandles: List<RecordedCandle>,
    ) {
        val liveTicks = capture.ticks.filterNot { it.warmup }
        val firstLiveMs = liveTicks.minOf { it.tick.timestamp }
        val lastLiveMs = liveTicks.maxOf { it.tick.timestamp }
        val containingStarts =
            replayCandles
                .map { it.candle }
                .filter { firstLiveMs >= it.startTime && firstLiveMs < it.endTime }
                .map { it.startTime }
        val fromMs = containingStarts.maxOrNull() ?: firstLiveMs
        val toMs =
            maxOf(
                lastLiveMs + 1L,
                replayCandles.maxOfOrNull { it.candle.endTime } ?: lastLiveMs + 1L,
            )
        val symbols =
            capture.ticks
                .map { it.tick.symbol }
                .toSet()
                .sorted()
        val timeframes =
            replayCandles
                .map { TimeWindow(it.candle.endTime - it.candle.startTime).canonicalSpec() }
                .toSet()
                .sorted()
        val materializedBars =
            replayCandles
                .groupBy {
                    TimeWindow(it.candle.endTime - it.candle.startTime).canonicalSpec() to it.provenance
                }.entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
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
                append(", \"candles\": ").append(capture.candles.size)
                append(", \"streamCandles\": ").append(capture.streamCandles.size)
                append(", \"materializedCandles\": ").append(replayCandles.size).append("},\n")
                append("  \"symbols\": [").append(symbols.joinToString(",") { jsonString(it) }).append("],\n")
                append("  \"timeframes\": [").append(timeframes.joinToString(",") { jsonString(it) }).append("],\n")
                append("  \"materializedBars\": [\n")
                materializedBars.forEachIndexed { index, (key, records) ->
                    append("    {\"timeframe\": ").append(jsonString(key.first))
                    append(", \"provenance\": ").append(jsonString(key.second))
                    append(", \"count\": ").append(records.size).append('}')
                    if (index != materializedBars.lastIndex) append(',')
                    append('\n')
                }
                append("  ]\n")
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
        optionalLong(record, field, source, lineNumber)
            ?: throw IllegalArgumentException("missing numeric $field at $source:$lineNumber")

    private fun optionalLong(
        record: JsonObject,
        field: String,
        source: String,
        lineNumber: Long,
    ): Long? {
        val raw = record[field]?.jsonPrimitive?.contentOrNull ?: return null
        return raw.toLongOrNull()
            ?: throw IllegalArgumentException("invalid numeric $field at $source:$lineNumber")
    }

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
        val sourceTimeframeMs: Long?,
        val tick: Tick,
    )

    private data class RecordedCandle(
        val sequence: Long,
        val candle: Candle,
        val provenance: String,
    )

    private data class Capture(
        val manifest: JsonObject,
        val ticks: List<RecordedTick>,
        val candles: List<RecordedCandle>,
        val streamCandles: List<RecordedCandle>,
    )

    private data class BarIdentity(
        val symbol: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
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
        const val STREAM_CANDLE_EVENT = "com.qkt.events.StreamCandleEvent"
        const val CAPTURED_CANDLE_EVENT = "CAPTURED_CANDLE_EVENT"
        const val CAPTURED_STREAM_CANDLE_EVENT = "CAPTURED_STREAM_CANDLE_EVENT"
        const val REHYDRATED_WARMUP_TICKS = "REHYDRATED_WARMUP_TICKS"
    }
}
