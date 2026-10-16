package com.qkt.marketdata.ingest.store

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One input file of an import and its SHA-256. */
@Serializable
data class ImportedInput(
    val name: String,
    val sha256: String,
)

/**
 * The record of one import: what was read, how it was interpreted, and what came of it. [id] is derived
 * from the input digests and the resolved descriptor, so importing the same files the same way again
 * has the same id and is recognised as unchanged.
 */
@Serializable
data class ImportEntry(
    val id: String,
    val inputs: List<ImportedInput>,
    val preset: String,
    val kind: String,
    val symbol: String,
    val clock: String? = null,
    val clockEvidence: String? = null,
    val basis: String? = null,
    val volume: String? = null,
    val timeframe: String? = null,
    val rows: Long,
    val records: Long,
    val notes: List<String> = emptyList(),
    val qkt: String,
    val importedAt: String,
    val comparisons: List<String> = emptyList(),
)

/**
 * The provenance file `.import.json` beside imported data (a tick symbol directory, a bar timeframe
 * directory, a funding or open-interest venue directory). [days] maps each imported day (or, for funding
 * and open interest, each contract name) to the [ImportEntry] that wrote it. qkt's readers never open it:
 * it is not a day file and not `manifest.json`, so it changes nothing about how data is read.
 */
@Serializable
data class ImportProvenance(
    val schema: String = SCHEMA,
    val imports: List<ImportEntry> = emptyList(),
    val days: Map<String, String> = emptyMap(),
) {
    /** The entry that wrote [key], if an import did. */
    fun entryFor(key: String): ImportEntry? = days[key]?.let { id -> imports.firstOrNull { it.id == id } }

    companion object {
        const val SCHEMA = "qkt-import-provenance-v1"
        const val FILE = ".import.json"
        private val json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

        /** The provenance in [dir], empty when there is none. */
        fun read(dir: Path): ImportProvenance {
            val file = dir.resolve(FILE)
            if (!Files.exists(file)) return ImportProvenance()
            return try {
                json.decodeFromString<ImportProvenance>(Files.readString(file))
            } catch (e: SerializationException) {
                error("corrupt import provenance at $file: ${e.message}")
            }
        }

        /** [dir]'s provenance with [entry] recorded for [keys], written atomically. */
        fun record(
            dir: Path,
            entry: ImportEntry,
            keys: Collection<String>,
        ) {
            val current = read(dir)
            val imports = current.imports.filter { it.id != entry.id } + entry
            val days = (current.days + keys.associateWith { entry.id }).toSortedMap()
            val live = days.values.toSet()
            write(dir, ImportProvenance(imports = imports.filter { it.id in live }, days = days))
        }

        /** Writes [provenance] to [dir] through a temporary file and an atomic rename. */
        fun write(
            dir: Path,
            provenance: ImportProvenance,
        ) {
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, ".import", ".json.tmp")
            Files.writeString(tmp, json.encodeToString(serializer(), provenance))
            Files.move(tmp, dir.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
