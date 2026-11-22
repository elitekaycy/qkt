package com.qkt.cli

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.VenueInstrumentSpec
import com.qkt.instrument.YamlInstrumentRegistry
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentsPullFlowStyleTest {
    @Test
    fun `an entry written as inline yaml is rewritten from its values when a pull adds another`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        Files.writeString(
            file,
            "instruments: [{qktSymbol: BACKTEST:BTCUSD, contractSize: 1, volumeStep: 0.01, volumeMin: 0.01, " +
                "pointSize: 0.01, digits: 2, tradeStopsLevelPoints: 0}]\n",
        )
        val pulled =
            VenueInstrumentSpec(
                InstrumentMeta(
                    qktSymbol = "BACKTEST:ETHUSD",
                    contractSize = BigDecimal("1"),
                    volumeStep = BigDecimal("0.01"),
                    volumeMin = BigDecimal("0.01"),
                    volumeMax = BigDecimal("50"),
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                ),
            )

        InstrumentsPull.write(file, listOf(pulled), source = "test")

        val registry = YamlInstrumentRegistry.load(file)
        assertThat(registry.lookup("BACKTEST:BTCUSD")?.digits).isEqualTo(2)
        assertThat(registry.lookup("BACKTEST:ETHUSD")?.volumeMax).isEqualByComparingTo("50")
    }
}
