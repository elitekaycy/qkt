package com.qkt.marketdata.ingest.source

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Picks the text encoding of an input and opens it as lines. A byte-order mark decides first (MetaTrader
 * writes UTF-16LE with one); BOM-less UTF-16 shows as NUL in every other byte; everything else must be
 * valid UTF-8, read strictly so a stray Latin-1 byte fails instead of becoming a replacement character in
 * a price. [forced] (`--encoding`) skips detection.
 */
object TextDecoding {
    private const val PROBE = 4096

    /** [input] as a reader in its detected (or [forced]) encoding, the BOM already consumed. */
    fun open(
        input: InputStream,
        forced: Charset? = null,
    ): BufferedReader {
        val stream = BufferedInputStream(input, PROBE * 2)
        stream.mark(PROBE)
        val head = stream.readNBytes(PROBE)
        stream.reset()
        val (charset, bom) = forced?.let { it to bomLength(head, it) } ?: detect(head)
        stream.skipNBytes(bom.toLong())
        val decoder =
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return BufferedReader(InputStreamReader(stream, decoder))
    }

    /** The encoding [head] is in, with the length of its byte-order mark (0 when it has none). */
    fun detect(head: ByteArray): Pair<Charset, Int> {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> StandardCharsets.UTF_8 to 3
            at(0) == 0xFF && at(1) == 0xFE -> StandardCharsets.UTF_16LE to 2
            at(0) == 0xFE && at(1) == 0xFF -> StandardCharsets.UTF_16BE to 2
            nulShare(head, odd = true) > HALF -> StandardCharsets.UTF_16LE to 0
            nulShare(head, odd = false) > HALF -> StandardCharsets.UTF_16BE to 0
            else -> StandardCharsets.UTF_8 to 0
        }
    }

    private fun bomLength(
        head: ByteArray,
        charset: Charset,
    ): Int {
        val (detected, bom) = detect(head)
        return if (detected == charset) bom else 0
    }

    private fun nulShare(
        head: ByteArray,
        odd: Boolean,
    ): Double {
        val positions = head.indices.filter { (it % 2 == 1) == odd }
        if (positions.size < MIN_PROBE) return 0.0
        return positions.count { head[it].toInt() == 0 }.toDouble() / positions.size
    }

    private const val HALF = 0.5
    private const val MIN_PROBE = 8
}
