package com.qkt.persistence

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FundingFileTest {
    private val funding =
        PersistedFunding(
            sinceMs = 1_000L,
            booked = mapOf("BTC-PERP" to 8L),
            closed = mapOf("BTC-PERP" to mapOf("leg-1" to BigDecimal("0.25"))),
        )

    @Test
    fun `funding with closed holdings survives a restart`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveFunding("alpha", funding)

        assertThat(FileStatePersistor(tmp).loadFunding("alpha")).isEqualTo(funding)
        assertThat(FileStatePersistor(tmp).loadFunding("beta")).isNull()
    }

    @Test
    fun `an unreadable funding file fails the load`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve("alpha")).resolve("funding.json").writeText("[")

        assertThatThrownBy { FileStatePersistor(tmp).loadFunding("alpha") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("loadFunding parse failed for alpha")
    }

    @Test
    fun `a funding file from another schema version is rejected`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveFunding("alpha", funding)
        val file = tmp.resolve("alpha/funding.json")
        file.writeText(file.readText().replace(Regex("\"version\":\\s*\\d+"), "\"version\":-1"))

        assertThatThrownBy { FileStatePersistor(tmp).loadFunding("alpha") }
            .hasMessageContaining("loadFunding schema mismatch for alpha")
    }
}
