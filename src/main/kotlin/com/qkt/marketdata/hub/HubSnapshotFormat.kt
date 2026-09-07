package com.qkt.marketdata.hub

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Decoder for `QKH1`, the qkt-data-hub snapshot format — the historical half of the hub contract.
 *
 * The layout mirrors the `QKB1` bar store's conventions (little-endian, a fixed header then
 * columnar `int64` blocks) for the same reason: a range scan over one column is one contiguous
 * read, and a reader in another language needs no framework to follow it.
 *
 * The trailing SHA-256 is checked on every decode and a mismatch is fatal. A snapshot is what a
 * backtest cites for reproducibility, so silently reading a file whose bytes have drifted from the
 * manifest would defeat the point of citing it at all.
 */
object HubSnapshotFormat {
    private val MAGIC = byteArrayOf('Q'.code.toByte(), 'K'.code.toByte(), 'H'.code.toByte(), '1'.code.toByte())
    const val VERSION: Int = 1
    const val SCALE: Int = 8
    const val NULL_SENTINEL: Long = Long.MIN_VALUE
    private const val NULL_DICT_INDEX = -1
    private const val TRAILER_BYTES = 32

    private val AVAILABILITY =
        arrayOf(
            HubRecord.AVAILABILITY_OBSERVED,
            HubRecord.AVAILABILITY_PUBLISHED,
            HubRecord.AVAILABILITY_DERIVED,
        )

    /** Field kinds a schema may declare. Only the numeric-bearing ones reach a snapshot body. */
    private const val TYPE_NUMBER = 0
    private const val TYPE_BOOL = 1
    private const val TYPE_TIMESTAMP = 2
    private const val TYPE_ENUM = 3

    /** Header facts a caller may want without decoding every record. */
    data class Header(
        val dataset: String,
        val schemaHash: String,
        val recordCount: Int,
        val fieldNames: List<String>,
    )

    class HubFormatException(
        message: String,
    ) : RuntimeException(message)

    fun read(path: Path): List<HubRecord> = decode(Files.readAllBytes(path)).second

    fun readHeader(path: Path): Header = decode(Files.readAllBytes(path)).first

    fun decode(bytes: ByteArray): Pair<Header, List<HubRecord>> {
        if (bytes.size < MAGIC.size + TRAILER_BYTES) {
            throw HubFormatException("snapshot is too short to be a QKH1 file (${bytes.size} bytes)")
        }
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) throw HubFormatException("not a QKH1 snapshot: bad magic")
        }
        verifyTrailer(bytes)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(MAGIC.size)
        val version = buffer.int
        if (version != VERSION) throw HubFormatException("unsupported QKH1 version $version")

        val schemaHash = ByteArray(32).also { buffer.get(it) }.joinToString("") { "%02x".format(it) }
        val dataset = readString(buffer)
        val recordCount = buffer.int
        val fieldCount = buffer.int
        val scale = buffer.int
        if (scale != SCALE) throw HubFormatException("unexpected QKH1 scale $scale")
        if (recordCount < 0 || fieldCount < 0) throw HubFormatException("negative counts in QKH1 header")

        val fieldNames = ArrayList<String>(fieldCount)
        val fieldTypes = IntArray(fieldCount)
        for (i in 0 until fieldCount) {
            fieldNames.add(readString(buffer))
            fieldTypes[i] = buffer.get().toInt()
            readString(buffer) // unit: carried for humans, not needed to decode a value
        }

        val scopes = readDictionary(buffer)
        val keys = readDictionary(buffer)
        val sources = readDictionary(buffer)
        readDictionary(buffer) // source_version
        readDictionary(buffer) // parser
        readDictionary(buffer) // raw_ref

        val knownAt = readLongColumn(buffer, recordCount)
        val effectiveAt = readLongColumn(buffer, recordCount)
        val periodStart = readLongColumn(buffer, recordCount)
        val periodEnd = readLongColumn(buffer, recordCount)
        val scopeIdx = readIntColumn(buffer, recordCount)
        val keyIdx = readIntColumn(buffer, recordCount)
        val revision = readIntColumn(buffer, recordCount)
        val availability = readByteColumn(buffer, recordCount)
        val seq = readLongColumn(buffer, recordCount)
        val sourceIdx = readIntColumn(buffer, recordCount)
        readIntColumn(buffer, recordCount) // source_version_idx
        readIntColumn(buffer, recordCount) // parser_idx
        readIntColumn(buffer, recordCount) // raw_ref_idx
        val fieldColumns = Array(fieldCount) { readLongColumn(buffer, recordCount) }

        val records = ArrayList<HubRecord>(recordCount)
        for (row in 0 until recordCount) {
            val fields = LinkedHashMap<String, BigDecimal?>(fieldCount)
            for (f in 0 until fieldCount) {
                fields[fieldNames[f]] = decodeValue(fieldColumns[f][row], fieldTypes[f])
            }
            records.add(
                HubRecord(
                    dataset = dataset,
                    scope = lookup(scopes, scopeIdx[row], "scope"),
                    key = lookup(keys, keyIdx[row], "key"),
                    revision = revision[row],
                    knownAt = knownAt[row],
                    effectiveAt = effectiveAt[row],
                    availability = availabilityName(availability[row]),
                    source = if (sourceIdx[row] == NULL_DICT_INDEX) "" else lookup(sources, sourceIdx[row], "source"),
                    seq = seq[row],
                    fields = fields,
                    periodStart = periodStart[row].takeIf { it != NULL_SENTINEL },
                    periodEnd = periodEnd[row].takeIf { it != NULL_SENTINEL },
                ),
            )
        }
        return Header(dataset, "sha256:$schemaHash", recordCount, fieldNames) to records
    }

    private fun decodeValue(
        raw: Long,
        type: Int,
    ): BigDecimal? =
        when {
            raw == NULL_SENTINEL -> null
            type == TYPE_NUMBER -> BigDecimal.valueOf(raw, SCALE).stripTrailingZeros()
            type == TYPE_BOOL || type == TYPE_TIMESTAMP || type == TYPE_ENUM -> BigDecimal.valueOf(raw)
            else -> BigDecimal.valueOf(raw)
        }

    private fun availabilityName(ordinal: Int): String =
        AVAILABILITY.getOrNull(ordinal) ?: throw HubFormatException("unknown availability ordinal $ordinal")

    private fun lookup(
        dictionary: List<String>,
        index: Int,
        what: String,
    ): String = dictionary.getOrNull(index) ?: throw HubFormatException("$what index $index is outside its dictionary")

    private fun verifyTrailer(bytes: ByteArray) {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, bytes.size - TRAILER_BYTES))
        val stored = bytes.copyOfRange(bytes.size - TRAILER_BYTES, bytes.size)
        if (!digest.contentEquals(stored)) {
            throw HubFormatException("QKH1 checksum mismatch: the snapshot's bytes have changed since it was written")
        }
    }

    private fun readString(buffer: ByteBuffer): String {
        val length = buffer.int
        if (length < 0 || length > buffer.remaining()) throw HubFormatException("QKH1 string length $length is invalid")
        val raw = ByteArray(length).also { buffer.get(it) }
        return String(raw, StandardCharsets.UTF_8)
    }

    private fun readDictionary(buffer: ByteBuffer): List<String> {
        val count = buffer.int
        if (count < 0) throw HubFormatException("QKH1 dictionary count $count is invalid")
        return List(count) { readString(buffer) }
    }

    private fun readLongColumn(
        buffer: ByteBuffer,
        count: Int,
    ): LongArray {
        if (buffer.remaining() < count * 8) throw HubFormatException("QKH1 body is truncated")
        return LongArray(count) { buffer.long }
    }

    private fun readIntColumn(
        buffer: ByteBuffer,
        count: Int,
    ): IntArray {
        if (buffer.remaining() < count * 4) throw HubFormatException("QKH1 body is truncated")
        return IntArray(count) { buffer.int }
    }

    private fun readByteColumn(
        buffer: ByteBuffer,
        count: Int,
    ): IntArray {
        if (buffer.remaining() < count) throw HubFormatException("QKH1 body is truncated")
        return IntArray(count) { buffer.get().toInt() }
    }
}
