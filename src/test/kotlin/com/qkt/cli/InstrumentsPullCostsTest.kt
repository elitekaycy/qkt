package com.qkt.cli

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.UnreportedCost
import com.qkt.instrument.VenueInstrumentSpec
import com.qkt.instrument.YamlInstrumentRegistry
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentsPullCostsTest {
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
            swapLongPoints = BigDecimal("-560"),
        )
    private val noCommission = mapOf(UnreportedCost.COMMISSION to "the symbol endpoint does not report commission")

    @Test
    fun `every known cost is written explicitly, zeros and defaults included`() {
        val text = InstrumentsPull.render(listOf(VenueInstrumentSpec(xau)), source = "test")

        assertThat(text).contains(
            "    commissionPerLot: 0\n",
            "    slippagePoints: 0\n",
            "    swapLongPoints: -560\n",
            "    swapShortPoints: 0\n",
            "    swapTripleDay: WEDNESDAY\n",
            "    swapRolloverHourUtc: 21\n",
        )
    }

    @Test
    fun `an unreported cost is a note naming the field and loads as unset`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        val unread = noCommission + (UnreportedCost.SWAP to "swap_mode 2 is not points")

        InstrumentsPull.write(file, listOf(VenueInstrumentSpec(xau, unread)), source = "test")

        val text = Files.readString(file)
        assertThat(text).contains(
            "    # commissionPerLot: not reported by the venue (the symbol endpoint does not report commission); set it by hand\n",
            "    # swapLongPoints: not reported by the venue (swap_mode 2 is not points); set it by hand\n",
        )
        assertThat(text).doesNotContain("    swapLongPoints:", "    commissionPerLot:")
        assertThat(
            YamlInstrumentRegistry.load(file).lookup("EXNESS:XAUUSD")!!.swapTripleDay,
        ).isEqualTo(DayOfWeek.WEDNESDAY)
    }

    @Test
    fun `a re-pull keeps the costs set by hand that the venue does not report`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        InstrumentsPull.write(file, listOf(VenueInstrumentSpec(xau, noCommission)), source = "test")
        Files.writeString(
            file,
            Files
                .readString(file)
                .replace(
                    Regex("    # commissionPerLot: .*\n"),
                    "    commissionPerLot: 3.5\n",
                ).replace("slippagePoints: 0", "slippagePoints: 5"),
        )

        InstrumentsPull.write(file, listOf(VenueInstrumentSpec(xau, noCommission)), source = "test")

        val kept = YamlInstrumentRegistry.load(file).lookup("EXNESS:XAUUSD")!!
        assertThat(kept.commissionPerLot).isEqualByComparingTo("3.5")
        assertThat(kept.slippagePoints).isEqualTo(5)
        assertThat(kept.swapLongPoints).isEqualByComparingTo("-560")
    }

    @Test
    fun `a re-pull of an unreported cost never set by hand keeps its note`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        InstrumentsPull.write(file, listOf(VenueInstrumentSpec(xau, noCommission)), source = "test")

        InstrumentsPull.write(file, listOf(VenueInstrumentSpec(xau, noCommission)), source = "test")

        assertThat(Files.readString(file)).contains("    # commissionPerLot: not reported by the venue")
    }

    @Test
    fun `entries the pull does not name stay exactly as written`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        val handWritten =
            "  - qktSymbol: EXNESS:EURUSD   # hand-tuned\n" +
                "    contractSize: 100000\n    volumeStep: 0.01\n    volumeMin: 0.01\n" +
                "    pointSize: 0.00001\n    digits: 5\n    tradeStopsLevelPoints: 0\n" +
                "    commissionPerLot: 3.5\n"
        Files.writeString(file, "instruments:\n$handWritten")

        InstrumentsPull.write(file, listOf(VenueInstrumentSpec(xau, noCommission)), source = "test")

        assertThat(Files.readString(file)).contains(handWritten)
        assertThat(YamlInstrumentRegistry.load(file).all().map { it.qktSymbol })
            .containsExactly("EXNESS:EURUSD", "EXNESS:XAUUSD")
    }
}
