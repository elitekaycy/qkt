package com.qkt.marketdata

import com.qkt.common.Money
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

class CsvTickFeed(
    private val path: Path,
) : TickFeed {
    private val reader: BufferedReader = openReader(path)
    private var lineNumber: Int = 1
    private var lastTimestamp: Long = Long.MIN_VALUE

    init {
        val header = reader.readLine() ?: error("empty CSV: $path")
        require(header == EXPECTED_HEADER) {
            "unexpected header at $path:1: got '$header', expected '$EXPECTED_HEADER'"
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
        val symbol = cols[1]
        check(symbol.isNotEmpty()) { "$path:$lineNumber: empty symbol: $line" }
        val price = parseOpt(cols[2], "price")
        val volume = parseOpt(cols[3], "volume")
        val bid = parseOpt(cols[4], "bid")
        val ask = parseOpt(cols[5], "ask")
        val bidVol = parseOpt(cols[6], "bidVolume")
        val askVol = parseOpt(cols[7], "askVolume")

        check(price != null || (bid != null && ask != null)) {
            "$path:$lineNumber: row needs price OR (bid AND ask): $line"
        }
        if (bid != null && ask != null) {
            check(bid <= ask) { "$path:$lineNumber: bid > ask: bid=$bid, ask=$ask" }
        }
        listOf(
            "price" to price,
            "bid" to bid,
            "ask" to ask,
            "volume" to volume,
            "bidVolume" to bidVol,
            "askVolume" to askVol,
        ).forEach { (name, v) ->
            if (v != null && v.signum() < 0) error("$path:$lineNumber: negative $name: $v")
        }

        val finalPrice =
            price
                ?: bid!!
                    .add(ask!!, Money.CONTEXT)
                    .divide(BigDecimal(2), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)

        return Tick(
            symbol = symbol,
            price = finalPrice,
            timestamp = ts,
            volume = volume,
            bid = bid,
            ask = ask,
            bidVolume = bidVol,
            askVolume = askVol,
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
