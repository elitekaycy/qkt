package com.qkt.marketdata

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads [Candle]s from a binary bar day-file ([BinaryBarFormat]). Bars are tiny (a day is tens of KB),
 * so it reads the whole file (served from the OS page cache after first read) and decodes the columnar
 * `int64` body with `BigDecimal.valueOf(stored, scale)`. Each candle's `endTime = startTime + timeframeMs`.
 */
class BinaryBarFeed(
    path: Path,
) {
    private val candles: List<Candle>

    init {
        val buf = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN)
        val header = BinaryBarFormat.readHeader(buf)
        val n = header.barCount
        val ts = LongArray(n) { buf.long }
        val open = LongArray(n) { buf.long }
        val high = LongArray(n) { buf.long }
        val low = LongArray(n) { buf.long }
        val close = LongArray(n) { buf.long }
        val volume = LongArray(n) { buf.long }
        val s = header.scale
        candles =
            (0 until n).map { i ->
                Candle(
                    symbol = header.symbol,
                    open = BigDecimal.valueOf(open[i], s),
                    high = BigDecimal.valueOf(high[i], s),
                    low = BigDecimal.valueOf(low[i], s),
                    close = BigDecimal.valueOf(close[i], s),
                    volume = BigDecimal.valueOf(volume[i], s),
                    startTime = ts[i],
                    endTime = ts[i] + header.timeframeMs,
                )
            }
    }

    fun candles(): List<Candle> = candles
}
