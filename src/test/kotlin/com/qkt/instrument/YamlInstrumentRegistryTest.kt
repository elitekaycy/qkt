package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class YamlInstrumentRegistryTest {
    private val fixture = Paths.get("src/test/resources/instruments/instruments.yaml")

    @Test
    fun `loads multiple instruments from yaml`() {
        val r = YamlInstrumentRegistry.load(fixture)
        val gold = r.require("EXNESS:XAUUSD")
        assertThat(gold.contractSize).isEqualByComparingTo("100")
        assertThat(gold.digits).isEqualTo(3)
        assertThat(gold.volumeStep).isEqualByComparingTo("0.01")
        assertThat(gold.volumeMax).isEqualByComparingTo("200")
        val eu = r.require("EXNESS:EURUSD")
        assertThat(eu.contractSize).isEqualByComparingTo("100000")
        assertThat(eu.digits).isEqualTo(5)
        assertThat(eu.volumeMax).isNull()
    }

    @Test
    fun `lookup returns null for unknown symbol`() {
        val r = YamlInstrumentRegistry.load(fixture)
        assertThat(r.lookup("BYBIT:DOGE")).isNull()
    }

    @Test
    fun `require throws with a helpful message for unknown symbol`() {
        val r = YamlInstrumentRegistry.load(fixture)
        val e = assertThrows<IllegalStateException> { r.require("BYBIT:DOGE") }
        assertThat(e.message).contains("BYBIT:DOGE").contains("instruments.yaml")
    }

    @Test
    fun `duplicate qktSymbol fails loudly at load`(
        @TempDir tmp: Path,
    ) {
        val dupe = tmp.resolve("instruments-dupe.yaml")
        Files.writeString(
            dupe,
            """
            instruments:
              - qktSymbol: X
                contractSize: 1
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.01
                digits: 2
                tradeStopsLevelPoints: 0
              - qktSymbol: X
                contractSize: 1
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.01
                digits: 2
                tradeStopsLevelPoints: 0
            """.trimIndent(),
        )
        val e = assertThrows<IllegalStateException> { YamlInstrumentRegistry.load(dupe) }
        assertThat(e.message).contains("duplicate qktSymbol 'X'")
    }

    @Test
    fun `missing required field fails with field name`(
        @TempDir tmp: Path,
    ) {
        val bad = tmp.resolve("instruments-bad.yaml")
        Files.writeString(
            bad,
            """
            instruments:
              - qktSymbol: X
                contractSize: 1
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.01
                digits: 2
                # tradeStopsLevelPoints missing
            """.trimIndent(),
        )
        val e = assertThrows<IllegalStateException> { YamlInstrumentRegistry.load(bad) }
        assertThat(e.message).contains("tradeStopsLevelPoints")
    }

    @Test
    fun `missing file fails with the path in the message`() {
        val e =
            assertThrows<IllegalStateException> {
                YamlInstrumentRegistry.load(Paths.get("/tmp/does-not-exist.yaml"))
            }
        assertThat(e.message).contains("/tmp/does-not-exist.yaml")
    }
}
