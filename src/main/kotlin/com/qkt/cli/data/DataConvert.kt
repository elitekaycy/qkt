package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.store.DataRoot
import java.nio.file.Files
import java.time.LocalDate

/**
 * `qkt data convert <symbol> [--from <date>] [--to <date>] [--prune] [--data-root <dir>]`
 *
 * Migrates a symbol's cached `.csv.gz` day files to the binary tick format (`.bin`), which the
 * data store then prefers for far faster decode. Idempotent — skips days already converted.
 * Leaves the CSV in place unless `--prune` is given. Optional `--from` (inclusive) / `--to`
 * (exclusive) bound the days converted.
 */
internal fun dataConvert(args: Args): Int {
    val symbol =
        args.positional(1) ?: run {
            System.err.println(
                "qkt: missing symbol. usage: qkt data convert <symbol> [--from] [--to] [--prune]",
            )
            return ExitCodes.ARG_ERROR
        }
    val root = DataRoot.forDataRoot(args.option("data-root"))
    val symDir = root.resolve("symbols").resolve(symbol)
    if (!Files.isDirectory(symDir)) {
        System.err.println("qkt: no cached tick data for '$symbol' at $symDir")
        return ExitCodes.USER_ERROR
    }
    val from = args.option("from")?.let { LocalDate.parse(it) }
    val to = args.option("to")?.let { LocalDate.parse(it) }
    val prune = args.flag("prune")

    val csvDays =
        Files.list(symDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".csv.gz") }
                .sorted()
                .toList()
        }
    val writer = BinaryTickWriter()
    var converted = 0
    var skipped = 0
    for (csv in csvDays) {
        val day = LocalDate.parse(csv.fileName.toString().removeSuffix(".csv.gz"))
        if (from != null && day.isBefore(from)) continue
        if (to != null && !day.isBefore(to)) continue
        val bin = symDir.resolve("$day.bin")
        if (Files.exists(bin)) {
            skipped++
            continue
        }
        val ticks = mutableListOf<Tick>()
        CsvTickFeed(csv).use { feed ->
            while (true) {
                val t = feed.next() ?: break
                ticks.add(t)
            }
        }
        writer.write(bin, symbol, ticks)
        if (prune) Files.deleteIfExists(csv)
        converted++
    }
    println("qkt data convert: $symbol — converted=$converted skipped=$skipped prune=$prune")
    return ExitCodes.SUCCESS
}
