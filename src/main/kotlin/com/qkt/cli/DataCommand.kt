package com.qkt.cli

import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DayFileIntegrity
import java.nio.file.Files

/**
 * `qkt data verify <symbol> [--data-root <dir>]`
 *
 * Reads every cached day file for a tick-store symbol and reports its quality — tick count and
 * largest intra-day gap — flagging empty, corrupt, or gappy days. The backtester keys coverage on
 * file *presence*, so a truncated or sparse day is otherwise concatenated in silently; this surfaces
 * it for any stream (not just Dukascopy). Exits non-zero when any day is flagged.
 */
class DataCommand(
    private val args: Args,
) {
    fun run(): Int =
        when (val action = args.positional(0)) {
            "verify" -> verify()
            else -> {
                System.err.println("qkt: unknown data action '${action ?: ""}' (expected: verify)")
                System.err.println("usage: qkt data verify <symbol> [--data-root <dir>]")
                ExitCodes.ARG_ERROR
            }
        }

    private fun verify(): Int {
        val symbol =
            args.positional(1) ?: run {
                System.err.println("qkt: missing symbol. usage: qkt data verify <symbol> [--data-root <dir>]")
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

    private companion object {
        /** Intra-day gap (ms) above which a day file is flagged as possibly partial. 6h. */
        private const val SUSPICIOUS_GAP_MS = 6 * 60 * 60 * 1000L
    }
}
