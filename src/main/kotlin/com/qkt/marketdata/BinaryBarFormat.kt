package com.qkt.marketdata

import com.qkt.common.Money
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * On-disk layout of a binary bar day-file (`<date>.bin`), format `qkt-bar-bin-v1`.
 *
 * A little-endian header then a columnar body: six `int64` blocks of [Header.barCount] values each —
 * `startTs` (raw epoch ms), then `open`/`high`/`low`/`close`/`volume` as scaled integers (a
 * `BigDecimal`'s unscaled value at scale [SCALE], so e.g. 1850.50000000 -> 185050000000). Reconstruct
 * a value with `BigDecimal.valueOf(stored, SCALE)`. Mirrors [BinaryTickFormat] for bars.
 */
object BinaryBarFormat {
    val MAGIC = byteArrayOf('Q'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    const val VERSION = 1
    const val SCALE = Money.SCALE // 8

    data class Header(
        val symbol: String,
        val barCount: Int,
        val timeframeMs: Long,
        val version: Int = VERSION,
        val scale: Int = SCALE,
    )

    fun writeHeader(
        buf: ByteBuffer,
        h: Header,
    ) {
        buf.put(MAGIC)
        buf.putInt(h.version)
        buf.putInt(h.scale)
        buf.putLong(h.timeframeMs)
        val sym = h.symbol.toByteArray(StandardCharsets.UTF_8)
        buf.putInt(sym.size)
        buf.put(sym)
        buf.putInt(h.barCount)
    }

    fun readHeader(buf: ByteBuffer): Header {
        val magic = ByteArray(4).also { buf.get(it) }
        check(magic.contentEquals(MAGIC)) { "bad magic: not a qkt-bar-bin file" }
        val version = buf.int
        check(version == VERSION) { "unsupported binary bar version: $version" }
        val scale = buf.int
        val timeframeMs = buf.long
        val symLen = buf.int
        val sym = ByteArray(symLen).also { buf.get(it) }
        val barCount = buf.int
        return Header(String(sym, StandardCharsets.UTF_8), barCount, timeframeMs, version, scale)
    }
}
