package com.qkt.instrument

import java.math.BigDecimal
import java.time.DayOfWeek
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InstrumentMetaValidationTest {
    private fun meta(
        volumeMax: BigDecimal? = null,
        commissionPerLot: BigDecimal = BigDecimal.ZERO,
        slippagePoints: Int = 0,
        minSpreadPoints: Int? = null,
        swapTripleDay: DayOfWeek = DayOfWeek.WEDNESDAY,
        spreadPoints: Int? = null,
        volumeStep: String = "0.01",
        volumeMin: String = "0.10",
        pointSize: String = "0.01",
        digits: Int = 2,
        swapRolloverHourUtc: Int = 21,
    ) = InstrumentMeta(
        qktSymbol = "X",
        contractSize = BigDecimal.ONE,
        volumeStep = BigDecimal(volumeStep),
        volumeMin = BigDecimal(volumeMin),
        volumeMax = volumeMax,
        pointSize = BigDecimal(pointSize),
        digits = digits,
        tradeStopsLevelPoints = 0,
        commissionPerLot = commissionPerLot,
        slippagePoints = slippagePoints,
        minSpreadPoints = minSpreadPoints,
        swapTripleDay = swapTripleDay,
        spreadPoints = spreadPoints,
        swapRolloverHourUtc = swapRolloverHourUtc,
    )

    @Test
    fun `a volume max under the volume min is refused`() {
        assertThatThrownBy { meta(volumeMax = BigDecimal("0.05")) }
            .hasMessage("InstrumentMeta.volumeMax must be >= volumeMin: 0.05 < 0.10")
    }

    @Test
    fun `negative commission, slippage and min spread are refused`() {
        assertThatThrownBy { meta(commissionPerLot = BigDecimal("-1")) }.hasMessageContaining("commissionPerLot")
        assertThatThrownBy { meta(slippagePoints = -1) }.hasMessageContaining("slippagePoints")
        assertThatThrownBy { meta(minSpreadPoints = -1) }.hasMessageContaining("minSpreadPoints")
    }

    @Test
    fun `a weekend triple-swap day is refused`() {
        assertThatThrownBy { meta(swapTripleDay = DayOfWeek.SATURDAY) }
            .hasMessageContaining("swapTripleDay must be Monday through Friday: SATURDAY")
    }

    @Test
    fun `non-positive steps, sizes and digits are refused by field`() {
        assertThatThrownBy { meta(volumeStep = "0") }.hasMessageContaining("volumeStep must be > 0")
        assertThatThrownBy { meta(volumeMin = "0") }.hasMessageContaining("volumeMin must be > 0")
        assertThatThrownBy { meta(pointSize = "0") }.hasMessageContaining("pointSize must be > 0")
        assertThatThrownBy { meta(digits = -1) }.hasMessageContaining("digits must be >= 0")
        assertThatThrownBy { meta(swapRolloverHourUtc = -1) }.hasMessageContaining("swapRolloverHourUtc")
    }

    @Test
    fun `a spread is fixed or floored, never negative and never both`() {
        assertThatThrownBy { meta(spreadPoints = -1) }.hasMessageContaining("spreadPoints must be >= 0: -1")
        assertThatThrownBy { meta(spreadPoints = 2, minSpreadPoints = 1) }
            .hasMessageContaining("set spreadPoints or minSpreadPoints, not both (X)")
        assertThat(meta(spreadPoints = 2).spreadPoints).isEqualTo(2)
        assertThat(meta(minSpreadPoints = 1).minSpreadPoints).isEqualTo(1)
    }

    @Test
    fun `a volume max at or above the min is kept`() {
        assertThat(meta(volumeMax = BigDecimal("0.10")).volumeMax).isEqualByComparingTo("0.10")
    }
}
