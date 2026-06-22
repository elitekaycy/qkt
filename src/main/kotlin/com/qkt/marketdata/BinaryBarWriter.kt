package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Writes [Candle]s to a binary bar day-file ([BinaryBarFormat]). Columnar: startTs then O/H/L/C/V. */
class BinaryBarWriter {
    fun write(
        path: Path,
        symbol: String,
        timeframeMs: Long,
        bars: List<Candle>,
    ) {
        val n = bars.size
        val symBytes = symbol.toByteArray(Charsets.UTF_8)
        val headerSize = 4 + 4 + 4 + 8 + 4 + symBytes.size + 4
        val buf =
            ByteBuffer.allocate(headerSize + n * 6 * Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        BinaryBarFormat.writeHeader(buf, BinaryBarFormat.Header(symbol, n, timeframeMs))
        for (b in bars) buf.putLong(b.startTime)
        for (b in bars) buf.putLong(scaled(b.open))
        for (b in bars) buf.putLong(scaled(b.high))
        for (b in bars) buf.putLong(scaled(b.low))
        for (b in bars) buf.putLong(scaled(b.close))
        for (b in bars) buf.putLong(scaled(b.volume))
        Files.createDirectories(path.parent)
        Files.write(path, buf.array())
    }

    private fun scaled(v: BigDecimal): Long = v.setScale(Money.SCALE, Money.ROUNDING).unscaledValue().longValueExact()
}
