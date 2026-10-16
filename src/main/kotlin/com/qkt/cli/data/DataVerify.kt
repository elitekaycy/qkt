package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.ingest.store.ImportProvenance
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DatasetSnapshots
import com.qkt.marketdata.store.DayFileIntegrity
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * `qkt data verify <symbol>` reports each cached tick day's count and largest gap, flagging empty,
 * corrupt or gappy days and the days with no file inside the stored span ([MissingDays]); `--snapshot
 * <file>` instead verifies a dataset snapshot. Non-zero when flagged.
 */
internal fun dataVerify(args: Args): Int {
    args.option("snapshot")?.let { return verifySnapshot(args, Path.of(it)) }
    val symbol =
        args.positional(1) ?: run {
            System.err.println(
                "qkt: missing symbol. usage: qkt data verify <symbol> [--data-root <dir>] or " +
                    "qkt data verify --snapshot <file> [--strict]",
            )
            return ExitCodes.ARG_ERROR
        }
    val root = DataRoot.forDataRoot(args.option("data-root"))
    val symDir = root.resolve("symbols").resolve(symbol)
    if (!Files.isDirectory(symDir)) {
        System.err.println("qkt: no cached tick data for '$symbol' at $symDir")
        return ExitCodes.USER_ERROR
    }
    val dayFiles =
        Files.list(symDir).use { stream ->
            stream
                .filter { p -> listOf(".csv", ".csv.gz", ".bin").any { p.fileName.toString().endsWith(it) } }
                .sorted()
                .toList()
        }
    if (dayFiles.isEmpty()) {
        System.err.println("qkt: no day files for '$symbol' under $symDir")
        return ExitCodes.USER_ERROR
    }

    println("qkt data verify: $symbol (${dayFiles.size} day files) under $symDir")
    val provenance = ImportProvenance.read(symDir)
    var flagged = 0
    var totalTicks = 0L
    val ticksByDay = mutableMapOf<LocalDate, Int>()
    var previousDay: String? = null
    var previousLastMs: Long? = null
    for (path in dayFiles) {
        val day =
            path.fileName
                .toString()
                .removeSuffix(".gz")
                .removeSuffix(".csv")
                .removeSuffix(".bin")
        val q = DayFileIntegrity.inspect(path)
        totalTicks += q.tickCount
        ticksByDay.merge(LocalDate.parse(day), q.tickCount, Int::plus)
        val boundary = if (day != previousDay) previousLastMs else null
        val status = DayStatus.of(q, LocalDate.parse(day), boundary, previousDay?.let { LocalDate.parse(it) })
        if (day != previousDay) {
            previousDay = day
            previousLastMs = q.lastMs ?: previousLastMs
        }
        if (status != "ok") flagged++
        val source = provenance.entryFor(day)?.let { "  imported ${it.preset}" }.orEmpty()
        println("  $day  ticks=${q.tickCount}  maxGap=${q.maxGapMs / 60_000}m  $status$source")
    }
    val missing = MissingDays.of(ticksByDay)
    missing.lines().forEach { println("  $it") }
    flagged += missing.days.size
    println("qkt data verify: done — ${dayFiles.size} days, $totalTicks ticks, $flagged flagged")
    return if (flagged > 0) ExitCodes.USER_ERROR else ExitCodes.SUCCESS
}

private fun verifySnapshot(
    args: Args,
    path: Path,
): Int {
    val snapshot =
        try {
            DatasetSnapshots.read(path)
        } catch (e: Exception) {
            System.err.println("qkt: error: cannot read snapshot $path: ${e.message}")
            return ExitCodes.USER_ERROR
        }
    val root = args.option("data-root")?.let { Path.of(it) }
    val result = DatasetSnapshots.verify(snapshot, dataRootOverride = root, strict = args.flag("strict"))
    if (result.ok) {
        println("qkt data verify: snapshot ${snapshot.id} ok (${snapshot.files.size} files)")
        return ExitCodes.SUCCESS
    }
    System.err.println("qkt data verify: snapshot ${snapshot.id} failed")
    for (failure in result.failures) System.err.println("  $failure")
    return ExitCodes.USER_ERROR
}
