package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class YamlInstrumentCurrencyTest {
    private fun registry(
        dir: Path,
        currencyLine: String,
    ): YamlInstrumentRegistry {
        val f = dir.resolve("instruments.yaml")
        Files.writeString(
            f,
            """
            instruments:
              - qktSymbol: EXNESS:US500
                contractSize: 1
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.01
                digits: 2
                tradeStopsLevelPoints: 0
            $currencyLine
            """.trimIndent(),
        )
        return YamlInstrumentRegistry.load(f)
    }

    @Test
    fun `currency is read when present`(
        @TempDir dir: Path,
    ) {
        assertThat(registry(dir, "    currency: USD").lookup("EXNESS:US500")?.currency).isEqualTo("USD")
    }

    @Test
    fun `currency stays absent when omitted`(
        @TempDir dir: Path,
    ) {
        assertThat(registry(dir, "").lookup("EXNESS:US500")?.currency).isNull()
    }

    @Test
    fun `a file with only a futures section has no instruments entries`(
        @TempDir dir: Path,
    ) {
        val f = dir.resolve("instruments.yaml")
        Files.writeString(
            f,
            "futures:\n  - { root: CME:ES, currency: USD, multiplier: 50, tickSize: 0.25, volumeStep: 1, volumeMin: 1 }\n",
        )
        assertThat(YamlInstrumentRegistry.load(f).all()).isEmpty()
    }
}
