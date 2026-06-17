package com.qkt.marketdata

import java.nio.file.Path

/**
 * Opens the right [TickFeed] for a cached day-file by extension: a `.bin` file is read by
 * [BinaryTickFeed] (fast columnar decode), anything else (`.csv`, `.csv.gz`) by [CsvTickFeed].
 *
 * Use this wherever a day-file path from the data store ([com.qkt.marketdata.store.DataStore.dayFile])
 * becomes a feed, so no caller hard-codes a single format — the store prefers `.bin` when present, so
 * a caller that hard-coded `CsvTickFeed` would try to parse a binary header as CSV and fail.
 */
fun openDayFeed(path: Path): TickFeed =
    if (path.fileName.toString().endsWith(".bin")) BinaryTickFeed(path) else CsvTickFeed(path)
