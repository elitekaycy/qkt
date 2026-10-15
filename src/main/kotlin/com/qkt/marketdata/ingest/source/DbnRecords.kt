package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads Databento DBN files (versions 1 to 3, uncompressed), one instrument a file. Each record starts
 * with its length in 4-byte words, its type, publisher, instrument id and `ts_event` (UTC nanoseconds);
 * prices are integers in units of 1e-9, converted exactly, with `i64::MAX` meaning "no price". Bars
 * (`ohlcv-1s/1m/1h/1d`) are timed by `ts_event`, their open; trades and top-of-book records (`trades`,
 * `mbp-1`, `tbbo`) by `ts_recv`, the order Databento sorts them in and the moment the data was known, and
 * a top-of-book record carries a trade price and size only when its action is a trade. A book with an empty
 * side has no mid, so its updates are skipped (counted in the input's notes) and its trades kept as trades. Symbol-mapping and
 * heartbeat records of a live capture are skipped; an error record is refused like any other. Other schemas
 * (order-by-order, depth, definitions, statistics, status) have no qkt series and are refused, as are
 * files holding several instruments or record types, and compressed `.dbn.zst` files.
 */
internal object DbnRecords {
    const val BARS_HEADER = "ts_event,open,high,low,close,volume"
    const val TRADES_HEADER = "ts_recv,price,size"
    const val QUOTES_HEADER = "ts_recv,bid,ask,bid_size,ask_size,price,size"
    private const val UNDEF_PRICE = Long.MAX_VALUE
    private const val PREFIX_BYTES = 8
    private const val WORD = 4
    private val BAR_TYPES = mapOf(32 to "1s", 33 to "1m", 34 to "1h", 35 to "1d")
    private const val TRADE = 0
    private const val MBP1 = 1

    /** Symbol-mapping and system (heartbeat) records a live capture interleaves with the data. */
    private val NOT_DATA = setOf(0x16, 0x17)
    private const val ONE_SIDED =
        "qkt stores a quote only with both sides; trades in a one-sided book are kept as trades"

    /** True when [path] is a DBN file, compressed or not. */
    fun isDbn(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.endsWith(".dbn") || name.endsWith(".dbn.zst")
    }

    /** The records of [path] as an input for the `databento-ohlcv`, `-trades` or `-mbp1` preset. */
    fun input(
        path: Path,
        sha256: String,
    ): ImportInput {
        if (path.fileName
                .toString()
                .lowercase()
                .endsWith(".zst")
        ) {
            throw ImportRefusal("$path is zstd-compressed: run `zstd -d ${path.fileName}` and import the .dbn file")
        }
        val b = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN)
        if (b.capacity() < PREFIX_BYTES || String(ByteArray(3) { b.get(it) }) != "DBN") {
            throw ImportRefusal("$path does not start with DBN: not a Databento binary file")
        }
        val version = b.get(3).toInt()
        if (version !in 1..3) throw ImportRefusal("$path: DBN version $version is not one qkt reads (1 to 3)")
        val rows = mutableListOf<String>()
        var type: Int? = null
        var instrument: Long? = null
        var oneSided = 0
        var at = PREFIX_BYTES + Integer.toUnsignedLong(b.getInt(4)).toInt()
        while (at < b.capacity()) {
            val length = (b.get(at).toInt() and 0xFF) * WORD
            if (length == 0 || at + length > b.capacity()) throw ImportRefusal("$path: record at byte $at is truncated")
            val rtype = (b.get(at + 1).toInt() and 0xFF)
            if (rtype in NOT_DATA) {
                at += length
                continue
            }
            val id = Integer.toUnsignedLong(b.getInt(at + 4))
            if (type != null && rtype != type) {
                throw ImportRefusal(
                    "$path mixes record types ${typeName(type)} and ${typeName(rtype)}: export one schema a file",
                )
            }
            if (instrument != null && id != instrument) {
                throw ImportRefusal("$path holds instruments $instrument and $id: export one instrument a file")
            }
            type = rtype
            instrument = id
            val row = row(path, b, at, rtype)
            if (row == null) oneSided++ else rows += row
            at += length
        }
        val kind = type ?: throw ImportRefusal("$path holds no records")
        val header =
            when (kind) {
                in BAR_TYPES -> BARS_HEADER
                TRADE -> TRADES_HEADER
                else -> QUOTES_HEADER
            }
        val csv = (listOf(header) + rows).joinToString("\n", postfix = "\n").toByteArray()
        val notes =
            if (oneSided >
                0
            ) {
                listOf("$oneSided book update(s) with an empty side skipped: $ONE_SIDED")
            } else {
                emptyList()
            }
        return ImportInput(path.fileName.toString(), sha256, timeframe = BAR_TYPES[kind], notes = notes) {
            ByteArrayInputStream(csv)
        }
    }

    private fun row(
        path: Path,
        b: ByteBuffer,
        at: Int,
        rtype: Int,
    ): String? {
        val tsEvent = java.lang.Long.toUnsignedString(b.getLong(at + 8))
        return when (rtype) {
            in BAR_TYPES -> {
                val prices = (0 until 4).joinToString(",") { price(b.getLong(at + 16 + it * 8)) }
                "$tsEvent,$prices,${java.lang.Long.toUnsignedString(b.getLong(at + 48))}"
            }
            TRADE -> "${recv(b, at)},${price(b.getLong(at + 16))},${Integer.toUnsignedString(b.getInt(at + 24))}"
            MBP1 -> {
                val trade = b.get(at + 28).toInt().toChar() == 'T'
                val px = if (trade) price(b.getLong(at + 16)) else ""
                val size = if (trade) Integer.toUnsignedString(b.getInt(at + 24)) else ""
                val twoSided = b.getLong(at + 48) != UNDEF_PRICE && b.getLong(at + 56) != UNDEF_PRICE
                if (!twoSided && !trade) return null
                val quote =
                    if (twoSided) {
                        val bidSize = Integer.toUnsignedString(b.getInt(at + 64))
                        val askSize = Integer.toUnsignedString(b.getInt(at + 68))
                        "${price(b.getLong(at + 48))},${price(b.getLong(at + 56))},$bidSize,$askSize"
                    } else {
                        ",,,"
                    }
                "${recv(b, at)},$quote,$px,$size"
            }
            else -> throw ImportRefusal(
                "$path holds ${typeName(rtype)} records, which qkt has no series for: export ohlcv-1s/1m/1h/1d, " +
                    "trades, mbp-1 or tbbo",
            )
        }
    }

    private fun recv(
        b: ByteBuffer,
        at: Int,
    ): String = java.lang.Long.toUnsignedString(b.getLong(at + 32))

    private fun price(raw: Long): String =
        if (raw == UNDEF_PRICE) "" else BigDecimal.valueOf(raw, 9).stripTrailingZeros().toPlainString()

    private fun typeName(rtype: Int): String =
        when (rtype) {
            TRADE -> "trades"
            MBP1 -> "mbp-1/tbbo"
            in BAR_TYPES -> "ohlcv-${BAR_TYPES[rtype]}"
            0x0A -> "mbp-10"
            0xA0 -> "mbo"
            0x13 -> "instrument definition"
            0x18 -> "statistics"
            0x12 -> "status"
            0x14 -> "imbalance"
            else -> "type 0x${rtype.toString(16)}"
        }
}
