package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** One text input to import: a plain file, the content of a `.gz`, or one member of a `.zip`. */
data class ImportInput(
    /** Display name used in reports and line references, e.g. `XAUUSD_M1.csv` or `data.zip!2024-01.csv`. */
    val name: String,
    /** SHA-256 of the file on disk (the archive for a zip member), recorded in provenance. */
    val sha256: String,
    private val opener: () -> InputStream,
) {
    /** A fresh stream over the input's bytes, decompressed. */
    fun open(): InputStream = opener()
}

/** The inputs found under the given paths, and the files a directory walk passed over. */
data class ImportExpansion(
    val inputs: List<ImportInput>,
    val skipped: List<Path>,
    /** Dukascopy `.bi5` hours that hold no ticks (the market was closed or quiet). */
    val emptyHours: Int = 0,
)

/**
 * Expands the paths given to `qkt data import` into text inputs, by content rather than by extension:
 * gzip and zip are opened, a directory is walked in name order, Dukascopy `.bi5` hours are decoded
 * ([Bi5Hours]), and other binary formats that need an export first are refused by name. A directory walk keeps only data files (`.csv`, `.txt`, `.json`, `.gz`,
 * `.zip`, ...) and lists the rest (checksums, download markers, notes) as skipped; a file named on the
 * command line is always read. Zip members that carry no data (`.CHECKSUM`, `MANIFEST.json`, macOS
 * metadata, and `.txt` reports beside `.csv` data, as HistData ships) are skipped.
 */
object ImportSources {
    private val DATA_EXTENSIONS =
        setOf("csv", "tsv", "txt", "json", "ndjson", "jsonl", "gz", "zip", "dat", "prn", "bi5")

    /** Every input under [paths], in a stable order (paths as given, directory entries and zip members by name). */
    fun expand(
        paths: List<Path>,
        bi5Divisor: Long? = null,
    ): ImportExpansion {
        val skipped = mutableListOf<Path>()
        var emptyHours = 0
        val inputs =
            paths.flatMap { path ->
                val files =
                    when {
                        Files.isDirectory(path) -> walk(path, skipped)
                        Files.isRegularFile(path) -> listOf(path)
                        else -> throw ImportRefusal("no such file or directory: $path")
                    }
                files.flatMap { file ->
                    if (!Bi5Hours.isBi5(file)) return@flatMap fromFile(file)
                    val hour = Bi5Hours.input(file, sha256(file), bi5Divisor)
                    if (hour == null) emptyHours++
                    listOfNotNull(hour)
                }
            }
        return ImportExpansion(inputs, skipped, emptyHours)
    }

    private fun walk(
        dir: Path,
        skipped: MutableList<Path>,
    ): List<Path> {
        val files =
            Files.walk(dir).use { stream ->
                stream
                    .filter {
                        Files.isRegularFile(it) &&
                            !dir.relativize(it).any { part -> part.toString().startsWith('.') }
                    }.sorted()
                    .toList()
            }
        val (data, other) =
            files.partition {
                it.fileName
                    .toString()
                    .substringAfterLast('.')
                    .lowercase() in
                    DATA_EXTENSIONS
            }
        skipped += other
        return data
    }

    private fun fromFile(path: Path): List<ImportInput> {
        val magic = Files.newInputStream(path).use { it.readNBytes(MAGIC_BYTES) }
        val name = path.fileName.toString()
        refuseBinary(name, magic)
        val digest = sha256(path)
        return when {
            magic.startsWith(GZIP) -> listOf(ImportInput(name, digest) { GZIPInputStream(Files.newInputStream(path)) })
            magic.startsWith(ZIP) -> zipMembers(path, digest)
            else -> listOf(ImportInput(name, digest) { Files.newInputStream(path) })
        }
    }

    private fun zipMembers(
        path: Path,
        digest: String,
    ): List<ImportInput> {
        val names =
            ZipFile(path.toFile()).use { zip ->
                zip
                    .entries()
                    .toList()
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .sorted()
            }
        val data = names.filter { !ignoredMember(it) }
        val kept = if (data.any { it.endsWith(".csv", true) }) data.filter { !it.endsWith(".txt", true) } else data
        if (kept.isEmpty()) throw ImportRefusal("$path holds no data files (members: ${names.joinToString()})")
        return kept.map { member ->
            ImportInput("${path.fileName}!$member", digest) {
                val zip = ZipFile(path.toFile())
                val entry = zip.getEntry(member)
                object : FilterInputStream(zip.getInputStream(entry)) {
                    override fun close() {
                        super.close()
                        zip.close()
                    }
                }
            }
        }
    }

    private fun ignoredMember(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return name.startsWith("__MACOSX/") ||
            base.startsWith('.') ||
            base.endsWith(".CHECKSUM") ||
            base.equals("MANIFEST.json", true)
    }

    private fun refuseBinary(
        name: String,
        magic: ByteArray,
    ) {
        val reason =
            when {
                name.endsWith(".hst", true) || name.endsWith(".hcc", true) -> "a MetaTrader history binary"
                name.endsWith(".scid", true) -> "a Sierra Chart .scid binary"
                name.endsWith(".dbn", true) || name.endsWith(".dbn.zst", true) -> "a Databento DBN binary"
                magic.startsWith(PARQUET) -> "a Parquet file"
                magic.startsWith(SEVEN_ZIP) || magic.startsWith(RAR) -> "a 7z/rar archive"
                else -> return
            }
        throw ImportRefusal("$name is $reason, which qkt data import does not read: export or extract it to CSV first")
    }

    private fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return HexFormat.of().formatHex(md.digest())
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private const val MAGIC_BYTES = 8
    private val GZIP = byteArrayOf(0x1f, 0x8b.toByte())
    private val ZIP = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val PARQUET = "PAR1".toByteArray()
    private val SEVEN_ZIP = byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte())
    private val RAR = "Rar!".toByteArray()
}
