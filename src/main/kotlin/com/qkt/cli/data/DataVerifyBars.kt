package com.qkt.cli.data

import com.qkt.candles.TimeWindow
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.BinaryBarFeed
import com.qkt.marketdata.Candle
import com.qkt.marketdata.ingest.store.ImportProvenance
import com.qkt.marketdata.store.ActivityRun
import com.qkt.marketdata.store.ActivityRunBuilder
import com.qkt.marketdata.store.BarDayCsv
import java.nio.BufferUnderflowException
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `qkt data verify SOURCE:SYMBOL` checks the stored bars of a fetched, imported or built series, one timeframe
 * folder at a time ([timeframe] limits it to one). A folder can hold two kinds of day file, checked apart:
 * `<day>.csv` (fetched or imported, what a plain backtest reads) and `<day>.bin` (the `--bars` research tier).
 * Each day reports its bar count and largest gap, and flags a file its reader refuses, bars out of order, off
 * the timeframe's grid or filed under another day, and impossible prices (a high below the open or close, a low
 * above them, a price at or below zero). Coverage (empty or gappy trading days, holidays, short sessions, trading
 * days with no file inside the stored span) is judged as in the tick check ([VerifyCoverage]). Non-zero when
 * anything is flagged or nothing is stored, e.g.
 * `2026-10-13  bars=1440  maxGap=1m  ok  imported binance-klines`.
 */
internal fun dataVerifyBars(
    root: Path,
    qktSymbol: String,
    timeframe: String?,
): Int {
    val seriesDir = root.resolve("bars").resolve(qktSymbol.substringBefore(':')).resolve(qktSymbol.substringAfter(':'))
    val tfDirs =
        if (!Files.isDirectory(seriesDir)) {
            emptyList()
        } else {
            Files
                .list(seriesDir)
                .use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }
                .filter { timeframe == null || it.fileName.toString() == timeframe }
        }
    var checked = 0
    var flagged = 0
    for (dir in tfDirs) {
        for (kind in BarFileKind.entries) {
            val files = Files.list(dir).use { s -> s.filter { kind.matches(it) }.sorted().toList() }
            if (files.isEmpty()) continue
            checked += files.size
            flagged += verifyDays(dir, qktSymbol, kind, files)
        }
    }
    if (checked == 0) {
        val what = timeframe?.let { " at $it" }.orEmpty()
        System.err.println("qkt: no stored bars for '$qktSymbol'$what under $seriesDir")
        return ExitCodes.USER_ERROR
    }
    return if (flagged > 0) ExitCodes.USER_ERROR else ExitCodes.SUCCESS
}

/** A bar day file's format and what reads it. */
private enum class BarFileKind(
    val extension: String,
    val readBy: String,
) {
    CSV(".csv", "read by backtests"),
    BIN(".bin", "the --bars tier"),
    ;

    fun matches(path: Path): Boolean = path.fileName.toString().matches(Regex("""\d{4}-\d{2}-\d{2}\Q$extension\E"""))

    fun read(
        path: Path,
        qktSymbol: String,
        windowMs: Long,
    ): List<Candle> = if (this == CSV) BarDayCsv.read(path, qktSymbol, windowMs) else BinaryBarFeed(path).candles()
}

/** One bar day file as read: its bar count and largest gap, what is wrong with it, and its coverage runs. */
private class BarDay(
    val name: String,
    val day: LocalDate,
    val bars: Int,
    val maxGap: String,
    val problem: String?,
    val runs: List<ActivityRun>,
)

private fun verifyDays(
    dir: Path,
    qktSymbol: String,
    kind: BarFileKind,
    files: List<Path>,
): Int {
    val tf = dir.fileName.toString()
    val windowMs = TimeWindow.parse(tf).durationMs
    println(
        "qkt data verify: $qktSymbol $tf ${kind.extension} bars, ${kind.readBy} (${files.size} day files) under $dir",
    )
    val provenance = ImportProvenance.read(dir)
    val read = files.map { readBarDay(it, qktSymbol, kind, windowMs, tf) }
    val tradesSaturdays = read.any { it.day.dayOfWeek == DayOfWeek.SATURDAY && it.bars > 0 }
    // Timeframes over a day keep one bar per several day files; only the file checks apply to them.
    val coverage =
        if (windowMs > DAY_MS) {
            null
        } else {
            VerifyCoverage(qktSymbol.substringAfter(':'), tradesSaturdays, "bar", read.associate { it.day to it.runs })
        }
    var flagged = 0
    var total = 0L
    for (d in read) {
        total += d.bars
        val verdict =
            d.problem?.let { VerifyVerdict(it, true) }
                ?: coverage?.of(d.day, d.bars == 0)
                ?: VerifyVerdict(if (d.bars == 0) "empty" else "ok", d.bars == 0)
        if (verdict.flagged) flagged++
        val source = provenance.entryFor(d.name)?.let { "  imported ${it.preset}" }.orEmpty()
        if (d.maxGap.isEmpty()) {
            println("  ${d.name}  ${verdict.text}")
        } else {
            println("  ${d.name}  bars=${d.bars}  maxGap=${d.maxGap}  ${verdict.text}$source")
        }
    }
    for (line in coverage?.absentLines().orEmpty()) {
        println("  ${line.text}")
        if (line.flagged) flagged++
    }
    println("qkt data verify: done — ${files.size} days, $total bars, $flagged flagged")
    return flagged
}

private fun readBarDay(
    path: Path,
    qktSymbol: String,
    kind: BarFileKind,
    windowMs: Long,
    tf: String,
): BarDay {
    val name = path.fileName.toString().removeSuffix(kind.extension)
    val day = LocalDate.parse(name)
    val bars =
        try {
            kind.read(path, qktSymbol, windowMs)
        } catch (e: IllegalArgumentException) {
            return BarDay(name, day, 0, "", "unreadable: ${e.message}", emptyList())
        } catch (e: BufferUnderflowException) {
            return BarDay(name, day, 0, "", "unreadable: the file is shorter than its header says", emptyList())
        }
    val problems = if (bars.isEmpty()) emptyList() else problems(bars, day, windowMs, tf)
    val runs = ActivityRunBuilder.of(bars.map { it.startTime }.toLongArray(), windowMs)
    return BarDay(name, day, bars.size, maxGapLabel(bars, windowMs), problems.joinToString(", ").ifEmpty { null }, runs)
}

private const val DAY_MS = 86_400_000L

/** What is wrong with one day's [bars], e.g. `2 off the 1m grid`; empty when the day is sound. */
private fun problems(
    bars: List<Candle>,
    day: LocalDate,
    windowMs: Long,
    tf: String,
): List<String> {
    val out = mutableListOf<String>()
    val unordered = bars.zipWithNext().count { (a, b) -> b.startTime <= a.startTime }
    if (unordered > 0) out += "$unordered out of order or repeated"
    val offGrid = bars.count { it.startTime % windowMs != 0L }
    if (offGrid > 0) out += "$offGrid off the $tf grid"
    val elsewhere = bars.count { Instant.ofEpochMilli(it.startTime).atZone(ZoneOffset.UTC).toLocalDate() != day }
    if (elsewhere > 0) out += "$elsewhere dated another day"
    val impossible =
        bars.count { b ->
            b.high < b.open.max(b.close) ||
                b.low > b.open.min(b.close) ||
                b.low.signum() <= 0
        }
    if (impossible > 0) out += "$impossible with impossible prices"
    return out
}

/** The longest stretch between two consecutive bars' starts, e.g. `1m` for a full day of 1m bars. */
private fun maxGapLabel(
    bars: List<Candle>,
    windowMs: Long,
): String {
    val gapMs = bars.zipWithNext { a, b -> b.startTime - a.startTime }.maxOrNull() ?: windowMs
    return if (gapMs >= 3_600_000L && gapMs % 3_600_000L == 0L) "${gapMs / 3_600_000L}h" else "${gapMs / 60_000L}m"
}
