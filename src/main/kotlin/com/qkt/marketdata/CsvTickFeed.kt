package com.qkt.marketdata

import com.qkt.common.Money
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Streams ticks from a CSV file at [path]. Transparently handles `.gz` compression.
 *
 * Header is auto-detected (timestamp / symbol / price columns). Throws on malformed
 * rows or non-monotonic timestamps — bad data should fail loud, not silently corrupt
 * the backtest.
 */
class CsvTickFeed(
    private val path: Path,
) : TickFeed {
    private val reader: BufferedReader = openReader(path)
    private var lineNumber: Int = 1
    private var lastTimestamp: Long = Long.MIN_VALUE

    init {
        try {
            val header = reader.readLine() ?: error("empty CSV: $path")
            require(header == EXPECTED_HEADER) {
                "unexpected header at $path:1: got '$header', expected '$EXPECTED_HEADER'"
            }
        } catch (t: Throwable) {
            runCatching { reader.close() }
            throw t
        }
    }

    override fun next(): Tick? {
        while (true) {
            val line = reader.readLine() ?: return null
            lineNumber++
            if (line.isEmpty()) continue
            val tick = parseLine(line)
            check(tick.timestamp >= lastTimestamp) {
                "$path:$lineNumber: non-decreasing timestamps required " +
                    "(got ${tick.timestamp}, last $lastTimestamp): $line"
            }
            lastTimestamp = tick.timestamp
            return tick
        }
    }

    override fun close() = reader.close()

    private fun parseLine(line: String): Tick {
        val cols = line.split(",")
        check(cols.size == 8) { "$path:$lineNumber: expected 8 columns, got ${cols.size}: $line" }
        val ts =
            cols[0].toLongOrNull()
                ?: error("$path:$lineNumber: invalid timestamp: '${cols[0]}'")
        return TickAssembler.assemble(
            symbol = cols[1],
            timestamp = ts,
            price = parseOpt(cols[2], "price"),
            volume = parseOpt(cols[3], "volume"),
            bid = parseOpt(cols[4], "bid"),
            ask = parseOpt(cols[5], "ask"),
            bidVolume = parseOpt(cols[6], "bidVolume"),
            askVolume = parseOpt(cols[7], "askVolume"),
            location = "$path:$lineNumber",
        )
    }

    private fun parseOpt(
        raw: String,
        field: String,
    ): BigDecimal? {
        if (raw.isEmpty()) return null
        return try {
            BigDecimal(raw).setScale(Money.SCALE, Money.ROUNDING)
        } catch (e: NumberFormatException) {
            error("$path:$lineNumber: invalid $field: '$raw'")
        }
    }

    private fun openReader(p: Path): BufferedReader {
        val raw = Files.newInputStream(p)
        val stream =
            if (p.fileName.toString().endsWith(".gz")) {
                GZIPInputStream(raw)
            } else {
                raw
            }
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
    }

    companion object {
        const val EXPECTED_HEADER: String = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"
    }
}
