package com.qkt.marketdata

import com.qkt.common.Money
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * On-disk layout of a binary tick day-file (`<date>.bin`), format `qkt-tick-bin-v1`.
 *
 * A file is a little-endian header followed by a columnar body: one `int64` block per stored
 * column, each holding [Header.tickCount] values in tick order. Values are scaled integers (a
 * `BigDecimal`'s unscaled value at scale [SCALE], so e.g. 1712.00200000 -> 171200200000); an absent
 * cell within a present column is [NULL_SENTINEL]. The timestamp column is always present and is not
 * flagged. A column entirely absent from the data is omitted and its presence bit is 0.
 *
 * The format records its [Header.codec] so readers stay format-driven; v1 ships [CODEC_RAW]
 * (uncompressed). Reconstructing a value is `BigDecimal.valueOf(stored, SCALE)`, which reproduces the
 * exact `BigDecimal` the CSV path parsed (the source has exactly [SCALE] fractional digits).
 */
object BinaryTickFormat {
    val MAGIC = byteArrayOf('Q'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
    const val VERSION = 1
    const val SCALE = Money.SCALE // 8
    const val CODEC_RAW = 0
    const val NULL_SENTINEL = Long.MIN_VALUE

    // Value-column bit positions in the presence flags (timestamp is implicit, always present).
    const val COL_PRICE = 0
    const val COL_VOLUME = 1
    const val COL_BID = 2
    const val COL_ASK = 3
    const val COL_BID_VOLUME = 4
    const val COL_ASK_VOLUME = 5
    const val COL_COUNT = 6

    data class Header(
        val symbol: String,
        val tickCount: Int,
        val presenceFlags: Int,
        val version: Int = VERSION,
        val scale: Int = SCALE,
        val codec: Int = CODEC_RAW,
    )

    fun isPresent(
        flags: Int,
        col: Int,
    ): Boolean = (flags and (1 shl col)) != 0

    fun writeHeader(
        buf: ByteBuffer,
        h: Header,
    ) {
        buf.put(MAGIC)
        buf.putInt(h.version)
        buf.putInt(h.scale)
        buf.putInt(h.codec)
        val sym = h.symbol.toByteArray(StandardCharsets.UTF_8)
        buf.putInt(sym.size)
        buf.put(sym)
        buf.putInt(h.tickCount)
        buf.putInt(h.presenceFlags)
    }

    fun readHeader(buf: ByteBuffer): Header {
        val magic = ByteArray(4).also { buf.get(it) }
        check(magic.contentEquals(MAGIC)) { "bad magic: not a qkt-tick-bin file" }
        val version = buf.int
        check(version == VERSION) { "unsupported binary tick version: $version" }
        val scale = buf.int
        val codec = buf.int
        check(codec == CODEC_RAW) { "unsupported binary tick codec: $codec" }
        val symLen = buf.int
        val sym = ByteArray(symLen).also { buf.get(it) }
        val tickCount = buf.int
        val presenceFlags = buf.int
        return Header(
            symbol = String(sym, StandardCharsets.UTF_8),
            tickCount = tickCount,
            presenceFlags = presenceFlags,
            version = version,
            scale = scale,
            codec = codec,
        )
    }
}
