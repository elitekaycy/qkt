package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Reads MetaTrader 4 history files (`<SYMBOL><PERIOD>.hst`). The 148-byte header holds the version, the
 * symbol, the period in minutes and the price digits; version 401 records are 60 bytes (int64 time, four
 * doubles, int64 tick volume, int32 spread, int64 real volume), version 400 records 44 bytes (int32 time,
 * then open, low, high, close and volume as doubles), little-endian. Times are the broker's server clock,
 * so rows are written as wall-clock times for the `mt4-hst` preset to read on its clock. Prices are stored
 * as doubles; each is rounded to the header's digits, the precision MetaTrader itself quotes, and those digits
 * become the instrument's unless `--digits` says otherwise.
 */
internal object HstBars {
    const val HEADER = "time,open,high,low,close,volume,spread"
    private const val HEADER_BYTES = 148
    private val WALL: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    /** True when [path] is an MT4 `.hst` file. */
    fun isHst(path: Path): Boolean = path.fileName.toString().endsWith(".hst", ignoreCase = true)

    /** The rows of [path] as an input for the `mt4-hst` preset. */
    fun input(
        path: Path,
        sha256: String,
    ): ImportInput {
        val buf = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.capacity() < HEADER_BYTES) throw ImportRefusal("$path is too short for an MT4 .hst header")
        val version = buf.getInt(0)
        val digits = buf.getInt(84)
        val recordBytes =
            when (version) {
                401 -> 60
                400 -> 44
                else -> throw ImportRefusal("$path: unknown .hst version $version (expected 400 or 401)")
            }
        val body = buf.capacity() - HEADER_BYTES
        val trailing = body % recordBytes
        if (trailing != 0) throw ImportRefusal("$path: $trailing trailing bytes; the file is truncated or not an .hst")
        val csv =
            buildString {
                append(HEADER).append('\n')
                for (i in 0 until body / recordBytes) {
                    val at = HEADER_BYTES + i * recordBytes
                    if (version == 401) row401(buf, at, digits) else row400(buf, at, digits)
                }
            }.toByteArray()
        return ImportInput(path.fileName.toString(), sha256, digits) { ByteArrayInputStream(csv) }
    }

    private fun StringBuilder.row401(
        b: ByteBuffer,
        at: Int,
        digits: Int,
    ) {
        val time = Instant.ofEpochSecond(b.getLong(at))
        val prices = (0 until 4).map { price(b.getDouble(at + 8 + it * 8), digits) }
        append(WALL.format(time)).append(',').append(prices.joinToString(","))
        append(',')
            .append(b.getLong(at + 40))
            .append(',')
            .append(b.getInt(at + 48))
            .append('\n')
    }

    private fun StringBuilder.row400(
        b: ByteBuffer,
        at: Int,
        digits: Int,
    ) {
        val time = Instant.ofEpochSecond(b.getInt(at).toLong())
        val (open, low, high, close) = (0 until 4).map { price(b.getDouble(at + 4 + it * 8), digits) }
        val volume = BigDecimal.valueOf(b.getDouble(at + 36)).stripTrailingZeros().toPlainString()
        append(WALL.format(time)).append(',').append(listOf(open, high, low, close).joinToString(","))
        append(',').append(volume).append(",\n")
    }

    private fun price(
        value: Double,
        digits: Int,
    ): String = BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
}
