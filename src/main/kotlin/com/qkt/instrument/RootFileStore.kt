package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * One JSON file per futures root under `<dataRoot>/contracts/<VENUE>/<ROOT><suffix>`, e.g.
 * `contracts/BINANCE_UM/BTCUSDT.json`. Shared by the per-root stores so paths, validation and
 * error messages stay identical.
 */
internal class RootFileStore<T>(
    private val dataRoot: Path,
    private val suffix: String,
    private val serializer: KSerializer<T>,
    private val rootOf: (T) -> String,
    private val what: String,
) {
    private val json = Json { prettyPrint = true }

    /** Where [root]'s file lives. */
    fun path(root: String): Path {
        QktSymbols.requireFileSafe(root)
        return dataRoot
            .resolve(
                "contracts",
            ).resolve(root.substringBefore(':'))
            .resolve("${root.substringAfter(':')}$suffix")
    }

    /** [root]'s value, or null when no file has been written; a malformed file fails naming its path. */
    fun read(root: String): T? {
        val file = path(root)
        if (!Files.exists(file)) return null
        val value =
            try {
                json.decodeFromString(serializer, Files.readString(file))
            } catch (e: IllegalArgumentException) {
                // kotlinx's SerializationException is an IllegalArgumentException, as are the value's
                // own validation failures; both mean the file is malformed.
                throw IllegalStateException("$file: invalid $what: ${e.message}", e)
            }
        require(rootOf(value) == root) { "$file holds $what for '${rootOf(value)}', expected '$root'" }
        return value
    }

    /** Writes [value] through a temporary file moved into place, so a reader never sees half a file. */
    fun write(value: T) {
        val file = path(rootOf(value))
        Files.createDirectories(file.parent)
        val staged = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(staged, json.encodeToString(serializer, value) + "\n")
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
