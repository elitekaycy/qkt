package com.qkt.marketdata.store

import com.qkt.evidence.EvidenceHasher
import com.qkt.marketdata.openDayFeed
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DatasetSnapshot(
    val schemaVersion: Int = 1,
    val id: String,
    val qktVersion: String,
    val gitSha: String,
    val dataRoot: String,
    val symbol: String,
    val vendor: String,
    val from: String,
    val to: String,
    val qualityPolicy: DataQualityPolicy = DataQualityPolicy(),
    val files: List<DatasetSnapshotFile>,
)

@Serializable
data class DataQualityPolicy(
    val mode: String = "strict",
    val maxGapMinutes: Long = 30,
    val allowEmptyDays: Boolean = false,
    val requireBidAsk: Boolean = false,
    val requireVolume: Boolean = false,
    val failOnCorruptDay: Boolean = true,
    val failOnMissingDay: Boolean = true,
)

@Serializable
data class DatasetSnapshotFile(
    val date: String,
    val path: String,
    val format: String,
    val lineage: String,
    val sizeBytes: Long,
    val sha256: String,
    val tickCount: Int,
    val minTimestamp: Long? = null,
    val maxTimestamp: Long? = null,
    val maxGapMs: Long = 0,
    val readable: Boolean = true,
    val bidAskTicks: Int = 0,
    val volumeTicks: Int = 0,
) {
    val empty: Boolean get() = readable && tickCount == 0
}

data class DatasetVerification(
    val ok: Boolean,
    val failures: List<String>,
)

object DatasetSnapshots {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    fun create(
        dataRoot: Path,
        symbol: String,
        from: LocalDate,
        to: LocalDate,
        vendor: String,
        qktVersion: String = "unknown",
        gitSha: String = "unknown",
        qualityPolicy: DataQualityPolicy = DataQualityPolicy(),
    ): DatasetSnapshot {
        require(from.isBefore(to)) { "--from must be before --to (exclusive)" }
        val root = dataRoot.toAbsolutePath().normalize()
        val store = DefaultDataStore(root = root, fetcher = null)
        val files =
            days(from, to).map { day ->
                val path =
                    store.dayFile(symbol, day)
                        ?: error("missing day file for $symbol $day under ${root.resolve("symbols").resolve(symbol)}")
                inspect(root, day, path)
            }
        val base =
            DatasetSnapshot(
                id = "pending",
                qktVersion = qktVersion,
                gitSha = gitSha,
                dataRoot = root.toString(),
                symbol = symbol,
                vendor = vendor,
                from = from.toString(),
                to = to.toString(),
                qualityPolicy = qualityPolicy,
                files = files,
            )
        val contentHash = sha256Text(toJson(base.copy(id = ""))).removePrefix("sha256:")
        return base.copy(id = "qkt-ds-${symbol.lowercase()}-${from}_$to-${contentHash.take(12)}")
    }

    fun toJson(snapshot: DatasetSnapshot): String = json.encodeToString(snapshot) + "\n"

    fun read(path: Path): DatasetSnapshot = json.decodeFromString(Files.readString(path))

    fun verify(
        snapshot: DatasetSnapshot,
        dataRootOverride: Path? = null,
        strict: Boolean = false,
    ): DatasetVerification {
        val root =
            dataRootOverride?.toAbsolutePath()?.normalize()
                ?: Path.of(snapshot.dataRoot).toAbsolutePath().normalize()
        val policy = if (strict) snapshot.qualityPolicy.copy(mode = "strict") else snapshot.qualityPolicy
        val failures = mutableListOf<String>()
        for (file in snapshot.files) {
            val path = root.resolve(file.path).normalize()
            if (!Files.exists(path)) {
                if (policy.failOnMissingDay) failures.add("${file.date}: missing ${file.path}")
                continue
            }
            val size = Files.size(path)
            if (size != file.sizeBytes) failures.add("${file.date}: size changed ${file.sizeBytes} -> $size")
            val hash = EvidenceHasher.sha256(path)
            if (hash != file.sha256) failures.add("${file.date}: sha256 changed")
            val now = inspect(root, LocalDate.parse(file.date), path)
            if (!now.readable && policy.failOnCorruptDay) failures.add("${file.date}: unreadable/corrupt")
            if (now.empty && !policy.allowEmptyDays) failures.add("${file.date}: empty day")
            if (now.maxGapMs > policy.maxGapMinutes * 60_000L) {
                failures.add("${file.date}: max gap ${now.maxGapMs / 60_000}m > ${policy.maxGapMinutes}m")
            }
            if (policy.requireBidAsk && now.tickCount > 0 && now.bidAskTicks != now.tickCount) {
                failures.add("${file.date}: bid/ask required but present on ${now.bidAskTicks}/${now.tickCount} ticks")
            }
            if (policy.requireVolume && now.tickCount > 0 && now.volumeTicks != now.tickCount) {
                failures.add("${file.date}: volume required but present on ${now.volumeTicks}/${now.tickCount} ticks")
            }
        }
        return DatasetVerification(ok = failures.isEmpty(), failures = failures)
    }

    private fun inspect(
        root: Path,
        day: LocalDate,
        path: Path,
    ): DatasetSnapshotFile {
        var count = 0
        var bidAskTicks = 0
        var volumeTicks = 0
        var maxGap = 0L
        var minTs: Long? = null
        var maxTs: Long? = null
        var last = Long.MIN_VALUE
        var readable = true
        try {
            openDayFeed(path).use { feed ->
                while (true) {
                    val tick = feed.next() ?: break
                    if (last != Long.MIN_VALUE) {
                        val gap = tick.timestamp - last
                        if (gap > maxGap) maxGap = gap
                    }
                    last = tick.timestamp
                    minTs = minTs?.coerceAtMost(tick.timestamp) ?: tick.timestamp
                    maxTs = maxTs?.coerceAtLeast(tick.timestamp) ?: tick.timestamp
                    if (tick.bid != null && tick.ask != null) bidAskTicks++
                    if (tick.volume != null) volumeTicks++
                    count++
                }
            }
        } catch (e: Exception) {
            readable = false
        }
        return DatasetSnapshotFile(
            date = day.toString(),
            path = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'),
            format = formatOf(path),
            lineage = "local tick day file (${formatOf(path)})",
            sizeBytes = Files.size(path),
            sha256 = EvidenceHasher.sha256(path),
            tickCount = count,
            minTimestamp = minTs,
            maxTimestamp = maxTs,
            maxGapMs = maxGap,
            readable = readable,
            bidAskTicks = bidAskTicks,
            volumeTicks = volumeTicks,
        )
    }

    private fun days(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { it.isBefore(to) }
            .toList()

    private fun sha256Text(text: String): String =
        "sha256:" +
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun formatOf(path: Path): String {
        val name = path.fileName.toString()
        return when {
            name.endsWith(".bin") -> "binary-ticks-v1"
            name.endsWith(".csv.gz") -> "csv-gzip"
            name.endsWith(".csv") -> "csv"
            else -> "unknown"
        }
    }
}
