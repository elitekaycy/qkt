package com.qkt.marketdata.hub

import java.math.BigDecimal
import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A field of a type this reader does not know is read as its raw stored integer, never dropped. */
class HubFormatUnknownTypeTest {
    private val trailerBytes = 32

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/cal.high_impact.qkh")).use { it.readAllBytes() }

    private fun indexOf(
        bytes: ByteArray,
        text: String,
    ): Int {
        val needle = text.toByteArray()
        return (0..bytes.size - needle.size).first { i -> needle.indices.all { bytes[i + it] == needle[it] } }
    }

    @Test
    fun `an unknown field type decodes to the raw stored value`() {
        val original = fixture()
        val (_, before) = HubSnapshotFormat.decode(original)
        val patched = original.copyOf()
        // A field is stored as name, then its one type byte.
        patched[indexOf(patched, "forecast") + "forecast".length] = 9
        val digest = MessageDigest.getInstance("SHA-256").digest(patched.copyOfRange(0, patched.size - trailerBytes))
        digest.copyInto(patched, patched.size - trailerBytes)

        val (_, after) = HubSnapshotFormat.decode(patched)

        val scaled = before.first { it.scope == "EUR" }.fields["forecast"]!!
        val raw = after.first { it.scope == "EUR" }.fields["forecast"]!!
        assertThat(raw).isEqualByComparingTo(scaled.movePointRight(8))
        assertThat(raw).isNotEqualByComparingTo(BigDecimal.ZERO)
    }
}
