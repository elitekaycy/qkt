package com.qkt.marketdata

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Streams [Tick]s from a binary day-file ([BinaryTickFormat]). Reconstructs each value with
 * `BigDecimal.valueOf(stored, scale)` (no string parsing) and routes through [TickAssembler], so it
 * yields the exact same `Tick` sequence as [CsvTickFeed] over the same data. Reads the whole file
 * into memory (a day is a few MB) and emits ticks in order; enforces the same monotonic-timestamp
 * contract and fails loud on a corrupt/truncated file.
 */
class BinaryTickFeed(
    private val path: Path,
) : TickFeed {
    private val header: BinaryTickFormat.Header
    private val timestamps: LongArray
    private val columns: Map<Int, LongArray>
    private var index: Int = 0
    private var lastTimestamp: Long = Long.MIN_VALUE

    init {
        val bytes = Files.readAllBytes(path)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header = BinaryTickFormat.readHeader(buf)
        val n = header.tickCount
        timestamps = LongArray(n) { buf.long }
        val cols = mutableMapOf<Int, LongArray>()
        for (col in 0 until BinaryTickFormat.COL_COUNT) {
            if (BinaryTickFormat.isPresent(header.presenceFlags, col)) {
                cols[col] = LongArray(n) { buf.long }
            }
        }
        columns = cols
    }

    override fun next(): Tick? {
        if (index >= header.tickCount) return null
        val i = index++
        val ts = timestamps[i]
        check(ts >= lastTimestamp) {
            "$path:${i + 1}: non-decreasing timestamps required (got $ts, last $lastTimestamp)"
        }
        lastTimestamp = ts
        return TickAssembler.assemble(
            symbol = header.symbol,
            timestamp = ts,
            price = decode(BinaryTickFormat.COL_PRICE, i),
            volume = decode(BinaryTickFormat.COL_VOLUME, i),
            bid = decode(BinaryTickFormat.COL_BID, i),
            ask = decode(BinaryTickFormat.COL_ASK, i),
            bidVolume = decode(BinaryTickFormat.COL_BID_VOLUME, i),
            askVolume = decode(BinaryTickFormat.COL_ASK_VOLUME, i),
            location = "$path:${i + 1}",
        )
    }

    private fun decode(
        col: Int,
        i: Int,
    ): BigDecimal? {
        val arr = columns[col] ?: return null
        val v = arr[i]
        return if (v == BinaryTickFormat.NULL_SENTINEL) null else BigDecimal.valueOf(v, header.scale)
    }
}
