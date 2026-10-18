package com.qkt.marketdata.store

import com.qkt.marketdata.BinaryBarFormat
import com.qkt.marketdata.openDayFeed
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Cheap reads of one binary bar day file ([BinaryBarFormat]) for coverage checks: the bar count from the header,
 * the start times from the first column, without decoding prices.
 */
internal object BinaryBarDayFile {
    /** Bytes before the symbol in the header: magic, version, scale, timeframe, symbol length. */
    private const val FIXED_HEADER_BYTES = 4 + 4 + 4 + 8 + 4

    /** The header's bar count; null when [path] does not exist, 0 when the file is too short to say. */
    fun barCount(path: Path): Int? {
        if (!Files.isRegularFile(path)) return null
        return FileChannel.open(path).use { channel ->
            val fixed = ByteBuffer.allocate(FIXED_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            if (channel.read(fixed) < FIXED_HEADER_BYTES) return 0
            val symLen = fixed.getInt(FIXED_HEADER_BYTES - 4)
            if (symLen < 0) return 0
            val count = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            if (channel.read(count, (FIXED_HEADER_BYTES + symLen).toLong()) < 4) return 0
            count.getInt(0).coerceAtLeast(0)
        }
    }

    /** Every bar's start time; null when [path] does not exist, empty when it cannot be read. */
    fun startTimes(path: Path): LongArray? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val buf = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN)
            val header = BinaryBarFormat.readHeader(buf)
            LongArray(header.barCount) { buf.long }
        }.getOrDefault(LongArray(0))
    }

    /** Whether the tick day file [source] holds at least one tick (reads only the first). */
    fun holdsTicks(source: Path): Boolean =
        runCatching { openDayFeed(source).use { it.next() != null } }.getOrDefault(true)
}
