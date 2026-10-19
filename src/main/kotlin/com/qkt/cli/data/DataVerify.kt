package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.CliDataRoot
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.ingest.store.ImportProvenance
import com.qkt.marketdata.store.DatasetSnapshots
import com.qkt.marketdata.store.DayFileIntegrity
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * `qkt data verify <symbol>` reports each cached tick day's count and largest gap, flagging corrupt files
 * ([DayStatus]) and coverage holes by the rule backtests apply ([VerifyCoverage]): empty or gappy trading days
 * and trading days with no file inside the stored span; holidays and holiday short sessions are shown, not
 * flagged; a
 * `SOURCE:SYMBOL` name checks that series' stored bars instead ([dataVerifyBars], `--tf` for one
 * timeframe); `--snapshot <file>` verifies a dataset snapshot. Non-zero when flagged.
 */
internal fun dataVerify(args: Args): Int {
    args.option("snapshot")?.let { return verifySnapshot(args, Path.of(it)) }
    val symbol =
        args.positional(1) ?: run {
            System.err.println(
                "qkt: missing symbol. usage: qkt data verify <symbol|SOURCE:SYMBOL> [--tf <tf>] " +
                    "[--data-root <dir>] or " +
                    "qkt data verify --snapshot <file> [--strict]",
            )
            return ExitCodes.ARG_ERROR
        }
    val root = CliDataRoot.of(args)
    if (':' in symbol) return dataVerifyBars(root, symbol, args.option("tf"))
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

    // One verdict per day, on the file backtests read (.bin before .csv.gz before .csv); another form of the same
    // day must hold the same ticks, or the store is not what it seems.
    val byDay =
        dayFiles.groupBy { LocalDate.parse(dayOf(it)) }.toSortedMap().mapValues { (_, files) ->
            files.sortedBy { f -> READ_ORDER.indexOfFirst { f.fileName.toString().endsWith(it) } }
        }
    println("qkt data verify: $symbol (${byDay.size} days) under $symDir")
    val provenance = ImportProvenance.read(symDir)
    val qualities = byDay.mapValues { (_, files) -> files.map(DayFileIntegrity::inspect) }
    // A market that never trades on Saturday keeps an empty Saturday file (qkt fetch writes one, an import records
    // one): that is a closed day, not a fault. On a 24/7 symbol an empty Saturday is a hole.
    val everyDay = qualities.any { (day, q) -> day.dayOfWeek == DayOfWeek.SATURDAY && q.first().tickCount > 0 }
    val coverage = VerifyCoverage(symbol, everyDay, "tick", qualities.mapValues { (_, q) -> q.first().runs })
    var flagged = 0
    var totalTicks = 0L
    var previousLastMs: Long? = null
    for ((date, files) in byDay) {
        val day = date.toString()
        val all = qualities.getValue(date)
        val q = all.first()
        totalTicks += q.tickCount
        var verdict =
            DayStatus.of(q, date, previousLastMs)?.let { VerifyVerdict(it, true) } ?: coverage.of(date, q.isEmpty)
        val other =
            all.drop(1).firstOrNull {
                it.tickCount != q.tickCount ||
                    it.firstMs != q.firstMs ||
                    it.lastMs != q.lastMs
            }
        if (other != null) {
            val text =
                "the ${files.first().fileName} and ${files[all.indexOf(other)].fileName} hold different ticks " +
                    "(${q.tickCount} vs ${other.tickCount}): re-run qkt data convert"
            verdict = VerifyVerdict(text, true)
        }
        if (q.lastMs != null) previousLastMs = q.lastMs
        if (verdict.flagged) flagged++
        val source = provenance.entryFor(day)?.let { "  imported ${it.preset}" }.orEmpty()
        println("  $day  ticks=${q.tickCount}  maxGap=${q.maxGapMs / 60_000}m  ${verdict.text}$source")
    }
    for (line in coverage.absentLines()) {
        println("  ${line.text}")
        if (line.flagged) flagged++
    }
    println("qkt data verify: done — ${byDay.size} days, $totalTicks ticks, $flagged flagged")
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

/** The order qkt's readers prefer a day's files in. */
private val READ_ORDER = listOf(".bin", ".csv.gz", ".csv")

private fun dayOf(path: java.nio.file.Path): String =
    path.fileName
        .toString()
        .removeSuffix(".gz")
        .removeSuffix(".csv")
        .removeSuffix(".bin")
