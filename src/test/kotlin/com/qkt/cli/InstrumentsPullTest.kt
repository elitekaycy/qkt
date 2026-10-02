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

class InstrumentsPullTest {
    private fun spec(
        symbol: String,
        contract: String,
    ) = VenueInstrumentSpec(
        InstrumentMeta(
            qktSymbol = symbol,
            contractSize = BigDecimal(contract),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = BigDecimal("50"),
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
        ),
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
    fun `an explicit currency round-trips and an absent one stays absent`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        val specs =
            listOf(
                spec("BACKTEST:US500", "1").let { it.copy(meta = it.meta.copy(currency = "USD")) },
                spec("BACKTEST:BTCUSD", "1"),
            )

        Files.writeString(file, InstrumentsPull.render(specs, source = "test"))

        val registry = YamlInstrumentRegistry.load(file)
        assertThat(registry.lookup("BACKTEST:US500")?.currency).isEqualTo("USD")
        assertThat(registry.lookup("BACKTEST:BTCUSD")?.currency).isNull()
    }

    @Test
    fun `a pull into a file keeps its futures section verbatim`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        val futures =
            "futures:\n  # hand-maintained roots\n" +
                "  - { root: CME:ES, currency: USD, multiplier: 50, tickSize: 0.25, volumeStep: 1, volumeMin: 1 }\n"
        Files.writeString(file, InstrumentsPull.render(listOf(spec("BACKTEST:BTCUSD", "1")), source = "test") + futures)

        InstrumentsPull.write(file, listOf(spec("BACKTEST:US500", "1")), source = "test")
        val first = Files.readString(file)
        InstrumentsPull.write(file, listOf(spec("BACKTEST:US500", "1")), source = "test")

        val text = Files.readString(file)
        assertThat(text).isEqualTo(first)
        assertThat(text).contains(futures)
        assertThat(
            com.qkt.instrument.FuturesRootsFile
                .load(file)
                .single()
                .root,
        ).isEqualTo("CME:ES")
        assertThat(
            YamlInstrumentRegistry.load(file).all().map {
                it.qktSymbol
            },
        ).containsExactly("BACKTEST:BTCUSD", "BACKTEST:US500")
    }

    @Test
    fun `a pull replaces the symbols it names and keeps the rest of the file`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        val before = listOf(spec("BACKTEST:BTCUSD", "1"), spec("BACKTEST:XAUUSD", "100"))
        Files.writeString(file, InstrumentsPull.render(before, source = "test"))

        InstrumentsPull.write(
            file,
            listOf(spec("BACKTEST:BTCUSD", "10"), spec("BACKTEST:ETHUSD", "1")),
            source = "test",
        )

        val merged = YamlInstrumentRegistry.load(file).all()
        assertThat(merged.map { it.qktSymbol }).containsExactly("BACKTEST:BTCUSD", "BACKTEST:ETHUSD", "BACKTEST:XAUUSD")
        assertThat(merged.first().contractSize).isEqualByComparingTo("10")
    }
}
