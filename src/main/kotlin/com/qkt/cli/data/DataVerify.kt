package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DatasetSnapshots
import com.qkt.marketdata.store.DayFileIntegrity
import java.nio.file.Files
import java.nio.file.Path

/** Intra-day gap (ms) above which a day file is flagged as possibly partial. 6h. */
private const val SUSPICIOUS_GAP_MS = 6 * 60 * 60 * 1000L

/**
 * `qkt data verify <symbol>` reports each cached tick day's count and largest gap, flagging empty,
 * corrupt or gappy days; `--snapshot <file>` instead verifies a dataset snapshot. Non-zero when flagged.
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
                .filter { it.fileName.toString().endsWith(".csv") || it.fileName.toString().endsWith(".csv.gz") }
                .sorted()
                .toList()
        }
    if (dayFiles.isEmpty()) {
        System.err.println("qkt: no day files for '$symbol' under $symDir")
        return ExitCodes.USER_ERROR
    }

    println("qkt data verify: $symbol (${dayFiles.size} day files) under $symDir")
    var flagged = 0
    var totalTicks = 0L
    for (path in dayFiles) {
        val day =
            path.fileName
                .toString()
                .removeSuffix(".gz")
                .removeSuffix(".csv")
        val q = DayFileIntegrity.inspect(path)
        totalTicks += q.tickCount
        val status =
            when {
                !q.readable -> "CORRUPT (unreadable)"
                q.isEmpty -> "EMPTY (0 ticks)"
                q.maxGapMs >= SUSPICIOUS_GAP_MS -> "GAP ${q.maxGapMs / 3_600_000}h"
                else -> "ok"
            }
        if (status != "ok") flagged++
        println("  $day  ticks=${q.tickCount}  maxGap=${q.maxGapMs / 60_000}m  $status")
    }
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
