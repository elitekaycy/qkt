package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class YamlInstrumentTickSizeTest {
    private fun registry(
        dir: Path,
        tickLine: String,
    ): YamlInstrumentRegistry {
        val f = dir.resolve("instruments.yaml")
        Files.writeString(
            f,
            """
            instruments:
              - qktSymbol: BROKER:US500
                contractSize: 1
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.01
                digits: 2
                tradeStopsLevelPoints: 0
            $tickLine
            """.trimIndent(),
        )
        return YamlInstrumentRegistry.load(f)
    }

    @Test
    fun `a tick size coarser than a point is the price step`(
        @TempDir dir: Path,
    ) {
        val meta = registry(dir, "    tickSize: 0.25").lookup("BROKER:US500")!!

        assertThat(meta.tickSize).isEqualByComparingTo("0.25")
        assertThat(meta.priceStep).isEqualByComparingTo("0.25")
        assertThat(meta.pointSize).isEqualByComparingTo("0.01")
    }

    @Test
    fun `without a tick size the price step is the point`(
        @TempDir dir: Path,
    ) {
        val meta = registry(dir, "").lookup("BROKER:US500")!!

        assertThat(meta.tickSize).isNull()
        assertThat(meta.priceStep).isEqualByComparingTo("0.01")
    }

    @Test
    fun `a tick size finer than a point leaves the price step at the point`(
        @TempDir dir: Path,
    ) {
        assertThat(registry(dir, "    tickSize: 0.001").lookup("BROKER:US500")!!.priceStep).isEqualByComparingTo("0.01")
    }

    @Test
    fun `a non-positive tick size is refused`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { registry(dir, "    tickSize: 0") }.hasMessageContaining("tickSize must be > 0")
    }
}
