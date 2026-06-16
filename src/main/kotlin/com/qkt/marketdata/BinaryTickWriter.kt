package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes a tick sequence to a binary day-file ([BinaryTickFormat]). Columns present in the body are
 * exactly those for which at least one tick has a non-null value (the price column is always present
 * because every [Tick] has a price). Writes to a temp file and atomically moves it into place,
 * matching the tick store's write discipline.
 */
class BinaryTickWriter {
    fun write(
        target: Path,
        symbol: String,
        ticks: List<Tick>,
    ) {
        Files.createDirectories(target.parent)
        var flags = 1 shl BinaryTickFormat.COL_PRICE
        if (ticks.any { it.volume != null }) flags = flags or (1 shl BinaryTickFormat.COL_VOLUME)
        if (ticks.any { it.bid != null }) flags = flags or (1 shl BinaryTickFormat.COL_BID)
        if (ticks.any { it.ask != null }) flags = flags or (1 shl BinaryTickFormat.COL_ASK)
        if (ticks.any { it.bidVolume != null }) flags = flags or (1 shl BinaryTickFormat.COL_BID_VOLUME)
        if (ticks.any { it.askVolume != null }) flags = flags or (1 shl BinaryTickFormat.COL_ASK_VOLUME)

        val header = BinaryTickFormat.Header(symbol = symbol, tickCount = ticks.size, presenceFlags = flags)
        val columns = presentColumns(flags)
        val bodyLongs = ticks.size.toLong() * (1 + columns.size)
        val cap = 64 + symbol.toByteArray().size + (bodyLongs * Long.SIZE_BYTES).toInt()
        val buf = ByteBuffer.allocate(cap).order(ByteOrder.LITTLE_ENDIAN)

        BinaryTickFormat.writeHeader(buf, header)
        for (t in ticks) buf.putLong(t.timestamp)
        for (col in columns) for (t in ticks) buf.putLong(scaled(field(t, col)))

        buf.flip()
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files
            .newByteChannel(
                tmp,
                setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
            ).use { it.write(buf) }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun presentColumns(flags: Int): List<Int> =
        (0 until BinaryTickFormat.COL_COUNT).filter { BinaryTickFormat.isPresent(flags, it) }

    private fun field(
        t: Tick,
        col: Int,
    ): BigDecimal? =
        when (col) {
            BinaryTickFormat.COL_PRICE -> t.price
            BinaryTickFormat.COL_VOLUME -> t.volume
            BinaryTickFormat.COL_BID -> t.bid
            BinaryTickFormat.COL_ASK -> t.ask
            BinaryTickFormat.COL_BID_VOLUME -> t.bidVolume
            BinaryTickFormat.COL_ASK_VOLUME -> t.askVolume
            else -> error("unknown column $col")
        }

    private fun scaled(bd: BigDecimal?): Long =
        if (bd == null) {
            BinaryTickFormat.NULL_SENTINEL
        } else {
            bd
                .setScale(Money.SCALE, Money.ROUNDING)
                .unscaledValue()
                .longValueExact()
        }
}
