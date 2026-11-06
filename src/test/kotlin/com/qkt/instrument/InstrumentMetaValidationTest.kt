package com.qkt.instrument

import java.math.BigDecimal
import java.time.DayOfWeek
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InstrumentMetaValidationTest {
    private fun meta(
        volumeMax: BigDecimal? = null,
        commissionPerLot: BigDecimal = BigDecimal.ZERO,
        slippagePoints: Int = 0,
        minSpreadPoints: Int? = null,
        swapTripleDay: DayOfWeek = DayOfWeek.WEDNESDAY,
    ) = InstrumentMeta(
        qktSymbol = "X",
        contractSize = BigDecimal.ONE,
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.10"),
        volumeMax = volumeMax,
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
        commissionPerLot = commissionPerLot,
        slippagePoints = slippagePoints,
        minSpreadPoints = minSpreadPoints,
        swapTripleDay = swapTripleDay,
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
}
