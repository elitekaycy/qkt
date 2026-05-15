package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InstrumentMetaTest {
    @Test
    fun `constructs with all fields`() {
        val m =
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
        assertThat(m.qktSymbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(m.contractSize).isEqualByComparingTo("100")
        assertThat(m.volumeMax).isEqualByComparingTo("200")
    }

    @Test
    fun `volumeMax is optional`() {
        val m =
            InstrumentMeta(
                qktSymbol = "X",
                contractSize = BigDecimal("1"),
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.01"),
                digits = 2,
                tradeStopsLevelPoints = 0,
            )
        assertThat(m.volumeMax).isNull()
    }

    @Test
    fun `rejects non-positive contractSize`() {
        assertThrows<IllegalArgumentException> {
            InstrumentMeta(
                qktSymbol = "X",
                contractSize = BigDecimal.ZERO,
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.01"),
                digits = 2,
                tradeStopsLevelPoints = 0,
            )
        }
    }

    @Test
    fun `rejects negative tradeStopsLevelPoints`() {
        assertThrows<IllegalArgumentException> {
            InstrumentMeta(
                qktSymbol = "X",
                contractSize = BigDecimal("1"),
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.01"),
                digits = 2,
                tradeStopsLevelPoints = -1,
            )
        }
    }

    @Test
    fun `rejects blank symbol`() {
        assertThrows<IllegalArgumentException> {
            InstrumentMeta(
                qktSymbol = "  ",
                contractSize = BigDecimal("1"),
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.01"),
                digits = 2,
                tradeStopsLevelPoints = 0,
            )
        }
    }
}
