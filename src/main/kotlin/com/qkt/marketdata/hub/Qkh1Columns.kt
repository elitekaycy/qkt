package com.qkt.marketdata.hub

import com.qkt.marketdata.hub.HubSnapshotFormat.HubFormatException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Bounds-checked primitive reads of a `QKH1` snapshot body for [HubSnapshotFormat]: length-
 * prefixed UTF-8 strings, string dictionaries, and fixed-width columns of `int64`, `int32` and
 * byte values. Every read that would overrun the buffer raises [HubFormatException].
 */
internal object Qkh1Columns {
    fun readString(buffer: ByteBuffer): String {
        val length = buffer.int
        if (length < 0 || length > buffer.remaining()) throw HubFormatException("QKH1 string length $length is invalid")
        val raw = ByteArray(length).also { buffer.get(it) }
        return String(raw, StandardCharsets.UTF_8)
    }

    fun readDictionary(buffer: ByteBuffer): List<String> {
        val count = buffer.int
        if (count < 0) throw HubFormatException("QKH1 dictionary count $count is invalid")
        return List(count) { readString(buffer) }
    }

    fun readLongColumn(
        buffer: ByteBuffer,
        count: Int,
    ): LongArray {
        if (buffer.remaining() < count * 8) throw HubFormatException("QKH1 body is truncated")
        return LongArray(count) { buffer.long }
    }

    fun readIntColumn(
        buffer: ByteBuffer,
        count: Int,
    ): IntArray {
        if (buffer.remaining() < count * 4) throw HubFormatException("QKH1 body is truncated")
        return IntArray(count) { buffer.int }
    }

    fun readByteColumn(
        buffer: ByteBuffer,
        count: Int,
    ): IntArray {
        if (buffer.remaining() < count) throw HubFormatException("QKH1 body is truncated")
        return IntArray(count) { buffer.get().toInt() }
    }
}
