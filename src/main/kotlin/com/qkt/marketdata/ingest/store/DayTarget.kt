package com.qkt.marketdata.ingest.store

import com.qkt.marketdata.ingest.record.IngestRecord
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * A store an import writes one UTC day file at a time (ticks, bars, series bars). [dir] holds the day
 * files, their manifest and the import's provenance. Implementations write through qkt's own formats and
 * atomic renames; [commit] records the written days in the store's manifest.
 */
interface DayTarget {
    val dir: Path

    /** The stored file(s) for [day], empty when the store has none. */
    fun stored(day: LocalDate): List<Path>

    /** Writes [day] holding [records] (sorted, validated), replacing nothing that [stored] still lists. */
    fun write(
        day: LocalDate,
        records: List<IngestRecord>,
    )

    /** [day]'s stored prices, read back through qkt's own readers (empty when nothing is stored). */
    fun readStored(day: LocalDate): DayPrices

    /** Records [days] as present in the store's manifest. */
    fun commit(days: Collection<LocalDate>)

    /** Moves [day]'s stored files under `.import-backup/<stamp>/` before a replacing import. */
    fun backup(
        day: LocalDate,
        stamp: String,
    ) {
        val target = dir.resolve(BACKUP).resolve(stamp)
        for (file in stored(day)) {
            Files.createDirectories(target)
            Files.move(file, target.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val BACKUP = ".import-backup"
    }
}
