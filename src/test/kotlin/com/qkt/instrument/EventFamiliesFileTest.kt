package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EventFamiliesFileTest {
    @TempDir
    lateinit var dir: Path

    private fun file(text: String): Path = dir.resolve("instruments.yaml").also { Files.writeString(it, text) }

    private val family = "  - { root: KALSHI:KXT, currency: USD, tickSize: 0.01, volumeStep: 1, volumeMin: 1 }\n"

    @Test
    fun `an events section declares each family`() {
        val families = EventFamiliesFile.load(file("events:\n$family"))
        assertThat(families).containsExactly(
            EventFamily("KALSHI:KXT", "USD", BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE),
        )
    }

    @Test
    fun `a file without an events section declares none and still loads its other entries`() {
        assertThat(EventFamiliesFile.load(file("instruments: []\n"))).isEmpty()
        assertThat(YamlInstrumentRegistry.load(file("events:\n$family")).all()).isEmpty()
    }

    @Test
    fun `a malformed section fails naming what is wrong`() {
        assertThatThrownBy { EventFamiliesFile.load(file("event:\n$family")) }.hasMessageContaining("did you mean")
        assertThatThrownBy { EventFamiliesFile.load(file("events: 3\n")) }.hasMessageContaining("must be a list")
        assertThatThrownBy { EventFamiliesFile.load(file("events:\n  - 3\n")) }.hasMessageContaining("must be a map")
        assertThatThrownBy { EventFamiliesFile.load(file("- 1\n")) }.hasMessageContaining("top-level")
        assertThatThrownBy { EventFamiliesFile.load(file("events:\n$family$family")) }
            .hasMessageContaining("duplicate events root 'KALSHI:KXT'")
    }

    @Test
    fun `a family entry is checked key by key`() {
        assertThatThrownBy {
            EventFamiliesFile.load(file("events:\n  - { root: KALSHI:KXT, currency: USD, tick: 0.01 }\n"))
        }.hasMessageContaining("unknown key(s) [tick]")
        assertThatThrownBy {
            EventFamiliesFile.load(file("events:\n  - { root: KALSHI:KXT, currency: USD, tickSize: 0.01 }\n"))
        }.hasMessageContaining("missing required key 'volumeStep'")
        assertThatThrownBy {
            EventFamiliesFile.load(
                file("events:\n  - { root: KALSHI:KXT, currency: USD, tickSize: x, volumeStep: 1, volumeMin: 1 }\n"),
            )
        }.hasMessageContaining("'tickSize' must be a number")
        assertThatThrownBy {
            EventFamiliesFile.load(
                file("events:\n  - { root: KXT, currency: USD, tickSize: 0.01, volumeStep: 1, volumeMin: 1 }\n"),
            )
        }.hasMessageContaining("events root KXT")
    }
}
