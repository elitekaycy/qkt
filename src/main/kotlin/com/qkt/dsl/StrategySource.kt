package com.qkt.dsl

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The file a strategy was compiled from, as qkt reports it to observers: its absolute [path], the
 * [sha256] of its whole content, and its [text] cut to [MAX_TEXT_BYTES] of UTF-8 on a character
 * boundary ([truncated] says it was cut). The hash always covers the whole file, so a cut text still
 * names the version that runs.
 *
 * e.g. `StrategySource.read(Path.of("strategies/gold.qkt"))` -> path `/srv/qkt/strategies/gold.qkt`,
 * sha256 `9f2c...`, the file's text, truncated false.
 */
data class StrategySource(
    /** The file's absolute, normalized path. */
    val path: String,
    /** Lowercase hex sha256 of the file's UTF-8 bytes. */
    val sha256: String,
    /** The file's text, at most [MAX_TEXT_BYTES] of UTF-8. */
    val text: String,
    /** Whether [text] is a prefix of a longer file. */
    val truncated: Boolean,
) {
    companion object {
        /** The most source text qkt reports for one strategy: 64 KiB, far above any real strategy file. */
        const val MAX_TEXT_BYTES = 64 * 1024

        /** The source of [path] whose content is [text]. */
        fun of(
            path: Path,
            text: String,
        ): StrategySource {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sha = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val cut = bytes.size > MAX_TEXT_BYTES
            return StrategySource(
                path = path.toAbsolutePath().normalize().toString(),
                sha256 = sha,
                text = if (cut) prefix(bytes) else text,
                truncated = cut,
            )
        }

        /** The source of the file at [path], or null when it cannot be read as UTF-8 text. */
        fun read(path: Path): StrategySource? =
            try {
                of(path, Files.readString(path))
            } catch (_: IOException) {
                null
            }

        // A multi-byte character split by the cap is dropped whole rather than decoded to U+FFFD.
        private fun prefix(bytes: ByteArray): String =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .decode(ByteBuffer.wrap(bytes, 0, MAX_TEXT_BYTES))
                .toString()
    }
}
