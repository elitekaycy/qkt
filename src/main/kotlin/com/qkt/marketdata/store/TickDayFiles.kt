package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * On-disk layout of the tick store's day files: `symbols/<symbol>/<day>.{bin,csv.gz,csv}`
 * under the store root. Resolves which encoding of a day exists (binary first), where a fetch
 * writes a new day, and which days a symbol directory holds.
 */
internal object TickDayFiles {
    /** The stored file for [symbol] on [day], preferring `.bin`, then `.csv.gz`, then `.csv`; null if none. */
    fun find(
        root: Path,
        symbol: String,
        day: LocalDate,
    ): Path? {
        val symDir = root.resolve("symbols").resolve(symbol)
        val bin = symDir.resolve("$day.bin")
        val gz = symDir.resolve("$day.csv.gz")
        val flat = symDir.resolve("$day.csv")
        return when {
            Files.exists(bin) -> bin
            Files.exists(gz) -> gz
            Files.exists(flat) -> flat
            else -> null
        }
    }

    /** Where a fetch of [symbol] on [day] writes its gzipped CSV. */
    fun fetchTarget(
        root: Path,
        symbol: String,
        day: LocalDate,
    ): Path = root.resolve("symbols").resolve(symbol).resolve("$day.csv.gz")

    /** Distinct ISO day names with a day file in [symDir], sorted ascending. */
    fun storedDays(symDir: Path): List<String> =
        Files.list(symDir).use { fs ->
            fs
                .map { it.fileName.toString() }
                .filter { it.endsWith(".csv") || it.endsWith(".csv.gz") || it.endsWith(".bin") }
                .map { it.removeSuffix(".gz").removeSuffix(".csv").removeSuffix(".bin") }
                .distinct()
                .sorted()
                .toList()
        }
}
