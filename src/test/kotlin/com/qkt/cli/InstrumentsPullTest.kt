package com.qkt.cli

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.YamlInstrumentRegistry
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentsPullTest {
    private fun spec(
        symbol: String,
        contract: String,
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal(contract),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = BigDecimal("50"),
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
    )

    @Test
    fun `pulled specs round-trip through the file a backtest reads`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")

        Files.writeString(file, InstrumentsPull.render(listOf(spec("BACKTEST:BTCUSD", "1")), source = "test"))

        val loaded = YamlInstrumentRegistry.load(file).lookup("BACKTEST:BTCUSD")!!
        assertThat(loaded.contractSize).isEqualByComparingTo("1")
        assertThat(loaded.volumeMax).isEqualByComparingTo("50")
        assertThat(loaded.digits).isEqualTo(2)
    }

    @Test
    fun `a pull replaces the symbols it names and keeps the rest of the file`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        val before = listOf(spec("BACKTEST:BTCUSD", "1"), spec("BACKTEST:XAUUSD", "100"))
        Files.writeString(file, InstrumentsPull.render(before, source = "test"))

        val merged = InstrumentsPull.merge(file, listOf(spec("BACKTEST:BTCUSD", "10"), spec("BACKTEST:ETHUSD", "1")))

        assertThat(merged.map { it.qktSymbol }).containsExactly("BACKTEST:BTCUSD", "BACKTEST:ETHUSD", "BACKTEST:XAUUSD")
        assertThat(merged.first().contractSize).isEqualByComparingTo("10")
    }
}
