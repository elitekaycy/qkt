package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads Sierra Chart intraday files (`.scid`): a 56-byte header (`SCID`, header size, record size 40)
 * then records of a UTC time, four float32 prices and four uint32 counts. The time is int64 microseconds
 * since 1899-12-30 in current files and a float64 day count in old ones, told apart per file by range. A
 * record whose Open is 0 or a sub-trade marker is one trade (Close the price, High the ask, Low the bid);
 * any other is a bar. Prices are float32, so each is rounded to the instrument's digits, which the import
 * must know; a zero or empty-marker bid or ask is no quote, and a trade missing either side keeps only its
 * price. Sierra's bid and ask volumes count trades at each side, not quoted sizes, so they are not
 * stored. A file mixing trades and bars, or a record size other than 40, is refused.
 */
internal object ScidRecords {
    const val TICKS_HEADER = "time,price,bid,ask,volume"
    const val BARS_HEADER = "time,open,high,low,close,volume"
    private const val HEADER_BYTES = 56
    private const val RECORD_BYTES = 40
    private const val SC_EPOCH_US = 25_569L * 86_400_000_000L
    private val MICROS = 2_000_000_000_000_000L..5_000_000_000_000_000L
    private const val FIRST_DAY = 20_000.0
    private const val LAST_DAY = 80_000.0
    private const val MARKER = -1.0e37f

    /** True when [path] is a Sierra Chart `.scid` file. */
    fun isScid(path: Path): Boolean = path.fileName.toString().endsWith(".scid", ignoreCase = true)

    /** The records of [path] as an input for the `sierra-scid-ticks` or `sierra-scid-bars` preset. */
    fun input(
        path: Path,
        sha256: String,
        digits: Int?,
    ): ImportInput {
        val b = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN)
        if (b.capacity() < HEADER_BYTES || String(ByteArray(4) { b.get(it) }) != "SCID") {
            throw ImportRefusal("$path does not start with SCID: not a Sierra Chart intraday file")
        }
        val headerBytes = b.getInt(4)
        if (b.getInt(8) != RECORD_BYTES || headerBytes < HEADER_BYTES) {
            throw ImportRefusal("$path: record size ${b.getInt(8)}, header $headerBytes is not a layout qkt reads")
        }
        val places = digits ?: throw ImportRefusal("$path stores float32 prices: pass --digits so qkt can round them")
        val body = b.capacity() - headerBytes
        if (body % RECORD_BYTES != 0) throw ImportRefusal("$path: ${body % RECORD_BYTES} trailing bytes; truncated")
        val count = body / RECORD_BYTES
        if (count == 0) throw ImportRefusal("$path holds no records")
        val micros = b.getLong(headerBytes) in MICROS
        if (!micros && b.getDouble(headerBytes) !in FIRST_DAY..LAST_DAY) {
            throw ImportRefusal("$path: the first record's time is neither SCDateTime microseconds nor days")
        }
        val ticks = isTick(b.getFloat(headerBytes + 8))
        val rows =
            (0 until count).map { i ->
                val at = headerBytes + i * RECORD_BYTES
                if (isTick(b.getFloat(at + 8)) != ticks) {
                    throw ImportRefusal("$path mixes trade records and bar records (record ${i + 1})")
                }
                val time = epochMicros(b, at, micros)
                val volume = Integer.toUnsignedString(b.getInt(at + 28))
                val p = { offset: Int -> price(b.getFloat(at + offset), places) }
                if (ticks) {
                    val (bid, ask) = p(16) to p(12)
                    val quote = if (bid.isEmpty() || ask.isEmpty()) "," else "$bid,$ask"
                    "$time,${p(20)},$quote,$volume"
                } else {
                    "$time,${p(8)},${p(12)},${p(16)},${p(20)},$volume"
                }
            }
        val csv = (listOf(if (ticks) TICKS_HEADER else BARS_HEADER) + rows).joinToString("\n", postfix = "\n")
        return ImportInput(path.fileName.toString(), sha256) { ByteArrayInputStream(csv.toByteArray()) }
    }

    private fun isTick(open: Float): Boolean = open == 0f || open < MARKER

    private fun epochMicros(
        b: ByteBuffer,
        at: Int,
        micros: Boolean,
    ): Long =
        if (micros) {
            b.getLong(at) - SC_EPOCH_US
        } else {
            BigDecimal(b.getDouble(at))
                .multiply(BigDecimal(86_400_000_000L))
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact() - SC_EPOCH_US
        }

    private fun price(
        value: Float,
        digits: Int,
    ): String {
        if (value.isNaN() || value <= 0f) return ""
        return BigDecimal(value.toDouble()).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
    }
}
