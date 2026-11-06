package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class YamlInstrumentShapesTest {
    private val base =
        "  - qktSymbol: ALPACA:AAPL\n    contractSize: 1\n    volumeStep: 1\n    volumeMin: 1\n" +
            "    pointSize: 0.01\n    digits: 2\n    tradeStopsLevelPoints: 0\n"

    private fun load(
        dir: Path,
        body: String,
    ) = YamlInstrumentRegistry.load(dir.resolve("instruments.yaml").also { Files.writeString(it, body) })

    @Test
    fun `the file must be a map with an instruments list unless it declares only derivatives`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { load(dir, "- a\n") }.hasMessageContaining("top-level must be a map (got class")
        assertThatThrownBy { load(dir, "other: 1\n") }.hasMessageContaining("missing 'instruments' list")
        assertThatThrownBy { load(dir, "instruments:\n  - 3\n") }.hasMessageContaining("entry 0 must be a map")
        assertThat(load(dir, "futures: []\n").all()).isEmpty()
        assertThat(load(dir, "options: []\n").all()).isEmpty()
    }

    @Test
    fun `optional fields are read and trimmed`(
        @TempDir dir: Path,
    ) {
        val extra =
            "    volumeMax: 100\n    commissionPerLot: 1.5\n    swapLongPoints: -2\n    swapShortPoints: 1\n" +
                "    swapRolloverHourUtc: 22\n    swapTripleDay: ' friday '\n    spreadPoints: 3\n" +
                "    currency: ' USD '\n    tickSize: 0.05\n    category: ' Stocks '\n    tradeMode: close-only\n"

        val meta = load(dir, "instruments:\n$base$extra").lookup("ALPACA:AAPL")!!

        assertThat(meta.volumeMax).isEqualByComparingTo("100")
        assertThat(meta.commissionPerLot).isEqualByComparingTo("1.5")
        assertThat(meta.swapLongPoints).isEqualByComparingTo("-2")
        assertThat(meta.swapShortPoints).isEqualByComparingTo("1")
        assertThat(meta.swapRolloverHourUtc).isEqualTo(22)
        assertThat(meta.swapTripleDay).isEqualTo(java.time.DayOfWeek.FRIDAY)
        assertThat(meta.spreadPoints).isEqualTo(3)
        assertThat(meta.currency).isEqualTo("USD")
        assertThat(meta.tickSize).isEqualByComparingTo("0.05")
        assertThat(meta.category).isEqualTo("Stocks")
        assertThat(meta.tradeMode).isEqualTo(TradeMode.CLOSE_ONLY)
    }

    @Test
    fun `a blank category is absent and a bad trade mode names the entry`(
        @TempDir dir: Path,
    ) {
        assertThat(load(dir, "instruments:\n$base    category: ' '\n").lookup("ALPACA:AAPL")?.category).isNull()
        assertThatThrownBy { load(dir, "instruments:\n$base    tradeMode: sometimes\n") }
            .hasMessageContaining("instruments.yaml: entry 0 unknown tradeMode 'sometimes'")
    }

    @Test
    fun `equity costs fill defaults and report an invalid figure by entry`(
        @TempDir dir: Path,
    ) {
        val costs =
            "    equityCosts:\n      commissionPerShare: 0.005\n      minCommission: 1\n" +
                "      maxCommissionRate: 0.01\n"

        val read = load(dir, "instruments:\n$base$costs").lookup("ALPACA:AAPL")?.equityCosts!!

        assertThat(read.commissionPerShare).isEqualByComparingTo("0.005")
        assertThat(read.minCommission).isEqualByComparingTo("1")
        assertThat(read.maxCommissionRate).isEqualByComparingTo("0.01")
        assertThat(read.secFeeRate).isEqualByComparingTo(BigDecimal.ZERO)
        assertThatThrownBy { load(dir, "instruments:\n$base    equityCosts:\n      tafPerShare: -1\n") }
            .hasMessageContaining("instruments.yaml: entry 0 ")
    }
}
