package com.qkt.marketdata

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Streams [Tick]s from a binary day-file ([BinaryTickFormat]) by memory-mapping the file and reading
 * each value's `long` straight from the mapped buffer with `BigDecimal.valueOf(stored, scale)` (no
 * string parsing, no intermediate `byte[]`/`long[]` copy), routed through [TickAssembler] — so it
 * yields the exact same `Tick` sequence as [CsvTickFeed] over the same data. Mapping the file lets
 * several processes (e.g. concurrent backtests) share the same OS page-cache pages; only the per-tick
 * `BigDecimal` is allocated here. Enforces the monotonic-timestamp contract and fails loud on a
 * corrupt/truncated file.
 */
class BinaryTickFeed(
    private val path: Path,
) : TickFeed {
    private val header: BinaryTickFormat.Header

    // Byte offset of the timestamps int64 block (just past the header).
    private val tsBase: Int

    // Byte offset of each column's int64 block, indexed by column id; -1 = column absent.
    private val colBase: IntArray
    private var buf: ByteBuffer?
    private var index: Int = 0

    // Exclusive upper bound on `index`; the whole file by default, narrowed by `slice`.
    private var endIndex: Int = 0
    private var lastTimestamp: Long = Long.MIN_VALUE

    init {
        val mapped =
            FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
            }
        // MappedByteBuffer defaults to BIG_ENDIAN; the format is little-endian. Setting it wrong
        // silently byte-swaps every value, so this must match BinaryTickWriter.
        mapped.order(ByteOrder.LITTLE_ENDIAN)
        header = BinaryTickFormat.readHeader(mapped)
        val n = header.tickCount
        endIndex = n
        // readHeader consumes the header with relative gets, leaving the position at the timestamps
        // block; the body is the timestamps block then each present column in ascending id order.
        tsBase = mapped.position()
        val bases = IntArray(BinaryTickFormat.COL_COUNT) { -1 }
        var slot = 0
        for (col in 0 until BinaryTickFormat.COL_COUNT) {
            if (BinaryTickFormat.isPresent(header.presenceFlags, col)) {
                bases[col] = tsBase + (1 + slot) * n * Long.SIZE_BYTES
                slot++
            }
        }
        colBase = bases
        buf = mapped
    }

    override fun next(): Tick? {
        val b = buf ?: return null
        if (index >= endIndex) return null
        val i = index++
        val ts = b.getLong(tsBase + i * Long.SIZE_BYTES)
        check(ts >= lastTimestamp) {
            "$path:${i + 1}: non-decreasing timestamps required (got $ts, last $lastTimestamp)"
        }
        lastTimestamp = ts
        return TickAssembler.assemble(
            symbol = header.symbol,
            timestamp = ts,
            price = decode(b, BinaryTickFormat.COL_PRICE, i),
            volume = decode(b, BinaryTickFormat.COL_VOLUME, i),
            bid = decode(b, BinaryTickFormat.COL_BID, i),
            ask = decode(b, BinaryTickFormat.COL_ASK, i),
            bidVolume = decode(b, BinaryTickFormat.COL_BID_VOLUME, i),
            askVolume = decode(b, BinaryTickFormat.COL_ASK_VOLUME, i),
            location = { "$path:${i + 1}" },
        )
    }

    /**
     * Reposition this feed to the half-open time window `[fromMs, toMs)`. After calling, [next]
     * yields exactly the ticks whose timestamp is in that range, in order, then null. Backed by a
     * binary search over the sorted timestamp column (O(log n)) — no scan of skipped ticks. Safe to
     * call repeatedly for forward windows. e.g. ticks at 0..9000 step 1000, `slice(3000, 7000)`
     * yields 3000, 4000, 5000, 6000.
     */
    fun slice(
        fromMs: Long,
        toMs: Long,
    ): BinaryTickFeed {
        val b = buf ?: return this
        index = lowerBound(b, fromMs)
        endIndex = lowerBound(b, toMs)
        lastTimestamp = Long.MIN_VALUE
        return this
    }

    // First index whose timestamp is >= target (or tickCount if none), via binary search on the
    // sorted timestamp column.
    private fun lowerBound(
        b: ByteBuffer,
        target: Long,
    ): Int {
        var lo = 0
        var hi = header.tickCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (b.getLong(tsBase + mid * Long.SIZE_BYTES) < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    override fun close() {
        val b = buf ?: return
        buf = null
        // A MappedByteBuffer keeps its OS file mapping until GC; on Windows that holds a lock that
        // blocks deleting the file (e.g. a test temp dir). Release it deterministically here. The
        // FileChannel was already closed after map(); after close() next() returns null.
        runCatching { CLEANER?.invoke(b) }
    }

    private fun decode(
        b: ByteBuffer,
        col: Int,
        i: Int,
    ): BigDecimal? {
        val base = colBase[col]
        if (base < 0) return null
        val v = b.getLong(base + i * Long.SIZE_BYTES)
        return if (v == BinaryTickFormat.NULL_SENTINEL) null else BigDecimal.valueOf(v, header.scale)
    }

    private companion object {
        // sun.misc.Unsafe.invokeCleaner releases a mapped buffer's native mapping on demand. Resolved
        // once; null on a JVM where it is unavailable, in which case the mapping frees on GC instead.
        private val CLEANER: ((ByteBuffer) -> Unit)? =
            runCatching {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafe =
                    unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                val invoke = unsafeClass.getMethod("invokeCleaner", ByteBuffer::class.java)
                val fn: (ByteBuffer) -> Unit = { buffer -> invoke.invoke(unsafe, buffer) }
                fn
            }.getOrNull()
    }
}
