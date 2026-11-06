package com.qkt.cli

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.UnreportedCost
import com.qkt.instrument.VenueInstrumentSpec
import com.qkt.instrument.YamlInstrumentRegistry
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentsPullSwapKeptTest {
    private val xau =
        InstrumentMeta(
            qktSymbol = "EXNESS:XAUUSD",
            contractSize = BigDecimal("100"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = BigDecimal("200"),
            pointSize = BigDecimal("0.001"),
            digits = 3,
            tradeStopsLevelPoints = 0,
        )
    private val noSwap = mapOf(UnreportedCost.SWAP to "swap_mode 2 is not points")

    @Test
    fun `a re-pull keeps a swap set by hand that the venue does not report`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        InstrumentsPull.write(file, listOf(VenueInstrumentSpec(xau, noSwap)), source = "test")
        Files.writeString(
            file,
            Files.readString(file).replace(Regex("    # swapLongPoints: .*\n"), "    swapLongPoints: -42\n"),
        )

        InstrumentsPull.write(file, listOf(VenueInstrumentSpec(xau, noSwap)), source = "test")

        val kept = YamlInstrumentRegistry.load(file).lookup("EXNESS:XAUUSD")!!
        assertThat(kept.swapLongPoints).isEqualByComparingTo("-42")
        assertThat(Files.readString(file)).doesNotContain("# swapLongPoints: not reported")
    }
}
