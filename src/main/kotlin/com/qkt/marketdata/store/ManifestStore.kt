package com.qkt.marketdata.store

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ManifestStore(
    private val root: Path,
    private val clock: Clock = SystemClock(),
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = false
        }

    fun read(symbol: String): Manifest {
        val path = manifestPath(symbol)
        if (!Files.exists(path)) {
            return Manifest(symbol = symbol)
        }
        val text = Files.readString(path)
        val manifest =
            try {
                json.decodeFromString<Manifest>(text)
            } catch (e: Exception) {
                error("corrupt manifest at $path: ${e.message}; run ./gradlew rebuildManifest to recover")
            }
        require(manifest.schemaVersion == 1) {
            "unsupported manifest schemaVersion at $path: ${manifest.schemaVersion}; expected 1"
        }
        require(manifest.schema == "qkt-csv-v1") {
            "unsupported manifest schema at $path: ${manifest.schema}; expected 'qkt-csv-v1'"
        }
        return manifest
    }

    fun write(manifest: Manifest) {
        val symDir = root.resolve("symbols").resolve(manifest.symbol)
        Files.createDirectories(symDir)
        val target = symDir.resolve("manifest.json")
        val tmp = symDir.resolve("manifest.json.tmp")
        val updated = manifest.copy(lastUpdated = Instant.ofEpochMilli(clock.now()).toString())
        Files.writeString(tmp, json.encodeToString(updated))
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun coalesce(
        existing: List<DayRange>,
        added: DayRange,
    ): List<DayRange> {
        val all = (existing + added).sortedBy { it.from }
        val merged = mutableListOf<DayRange>()
        for (range in all) {
            val last = merged.lastOrNull()
            if (last != null && last.to >= range.from) {
                merged[merged.size - 1] = DayRange(last.from, maxOf(last.to, range.to))
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    private fun manifestPath(symbol: String): Path = root.resolve("symbols").resolve(symbol).resolve("manifest.json")
}
