package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.CliDataRoot
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import com.qkt.marketdata.openDayFeed
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * `qkt data convert <symbol> [--from <date>] [--to <date>] [--prune] [--data-root <dir>]`
 *
 * Migrates a symbol's cached `.csv.gz` day files to the binary tick format (`.bin`), which the
 * data store then prefers for far faster decode. Idempotent — skips days already converted.
 * Leaves the CSV in place unless `--prune` is given; `--prune` deletes a day's CSV, converted now or earlier, only
 * after its `.bin` reads back as the same ticks. Optional `--from` (inclusive) / `--to` (exclusive) bound the days.
 */
internal fun dataConvert(args: Args): Int {
    val symbol =
        args.positional(1) ?: run {
            System.err.println(
                "qkt: missing symbol. usage: qkt data convert <symbol> [--from] [--to] [--prune]",
            )
            return ExitCodes.ARG_ERROR
        }
    val root = CliDataRoot.of(args)
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
    var pruned = 0
    for (csv in csvDays) {
        val day = LocalDate.parse(csv.fileName.toString().removeSuffix(".csv.gz"))
        if (from != null && day.isBefore(from)) continue
        if (to != null && !day.isBefore(to)) continue
        val bin = symDir.resolve("$day.bin")
        val ticks = if (Files.exists(bin) && !prune) null else readAll(csv)
        if (Files.exists(bin)) {
            skipped++
        } else {
            writer.write(bin, symbol, ticks!!)
            converted++
        }
        if (!prune) continue
        // The CSV goes only once the .bin is proven to read back as the same ticks, a day converted earlier included.
        val back = readAll(bin)
        if (!sameTicks(ticks!!, back)) {
            System.err.println(
                "qkt: error: $bin does not read back as $csv (${back.size} vs ${ticks.size} ticks): kept the CSV; " +
                    "delete the .bin and convert again",
            )
            return ExitCodes.USER_ERROR
        }
        Files.delete(csv)
        pruned++
    }
    println("qkt data convert: $symbol — converted=$converted skipped=$skipped prune=$prune" + if (prune) " pruned=$pruned" else "")
    return ExitCodes.SUCCESS
}

private fun readAll(path: Path): List<Tick> =
    openDayFeed(path).use { feed -> generateSequence { feed.next() }.toList() }

/** Field by field, values compared by number (a .bin holds them at qkt's scale). */
private fun sameTicks(
    a: List<Tick>,
    b: List<Tick>,
): Boolean {
    fun eq(
        x: java.math.BigDecimal?,
        y: java.math.BigDecimal?,
    ) = if (x == null || y == null) x == y else x.compareTo(y) == 0
    return a.size == b.size &&
        a.zip(b).all { (x, y) ->
            x.timestamp == y.timestamp && x.symbol == y.symbol && eq(x.price, y.price) && eq(x.volume, y.volume) &&
                eq(x.bid, y.bid) && eq(x.ask, y.ask) && eq(x.bidVolume, y.bidVolume) && eq(x.askVolume, y.askVolume)
        }
}
