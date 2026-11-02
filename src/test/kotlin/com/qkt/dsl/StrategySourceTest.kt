package com.qkt.dsl

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategySourceTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `reads the whole file with its absolute path and the sha256 of its bytes`(
        @TempDir tmp: Path,
    ) {
        val text = "STRATEGY gold VERSION 1\n// été\n"
        val file = tmp.resolve("gold.qkt").also { Files.writeString(it, text) }

        val source = StrategySource.read(file)!!

        assertThat(source.path).isEqualTo(file.toAbsolutePath().normalize().toString())
        assertThat(source.sha256).isEqualTo(sha256(text.toByteArray(Charsets.UTF_8)))
        assertThat(source.text).isEqualTo(text)
        assertThat(source.truncated).isFalse
    }

    @Test
    fun `cuts a long text on a character boundary and keeps the hash of the whole file`(
        @TempDir tmp: Path,
    ) {
        // one byte, then two-byte characters: the byte cap lands inside one, which is dropped whole
        val text = "a" + "\u00e9".repeat(StrategySource.MAX_TEXT_BYTES)
        val file = tmp.resolve("long.qkt").also { Files.writeString(it, text) }

        val source = StrategySource.read(file)!!

        assertThat(source.truncated).isTrue
        assertThat(source.text.toByteArray(Charsets.UTF_8).size).isEqualTo(StrategySource.MAX_TEXT_BYTES - 1)
        assertThat(text).startsWith(source.text)
        assertThat(source.sha256).isEqualTo(sha256(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a text of exactly the cap is sent whole`() {
        val text = "a".repeat(StrategySource.MAX_TEXT_BYTES)

        val source = StrategySource.of(Path.of("/s/a.qkt"), text)

        assertThat(source.truncated).isFalse
        assertThat(source.text).isEqualTo(text)
    }

    @Test
    fun `an unreadable file has no source`(
        @TempDir tmp: Path,
    ) {
        assertThat(StrategySource.read(tmp.resolve("missing.qkt"))).isNull()
    }
}
