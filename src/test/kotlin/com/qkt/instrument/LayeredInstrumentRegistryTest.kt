package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LayeredInstrumentRegistryTest {
    private fun meta(
        symbol: String,
        commission: String,
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal("100"),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = null,
        pointSize = BigDecimal("0.001"),
        digits = 3,
        tradeStopsLevelPoints = 0,
        commissionPerLot = BigDecimal(commission),
    )

    @Test
    fun `an earlier layer overrides a later one`() {
        val yaml =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String) =
                    if (qktSymbol == "BACKTEST:XAUUSD") meta(qktSymbol, "7.0") else null
            }
        val layered = LayeredInstrumentRegistry(listOf(yaml, StandardInstrumentRegistry))

        // yaml wins for the symbol it defines (commission 7, not the standard 0)
        assertThat(layered.lookup("BACKTEST:XAUUSD")!!.commissionPerLot)
            .isEqualByComparingTo(BigDecimal("7.0"))
    }

    @Test
    fun `a symbol absent from the earlier layer falls through to the later one`() {
        val yaml =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String) =
                    if (qktSymbol == "BACKTEST:XAUUSD") meta(qktSymbol, "7.0") else null
            }
        val layered = LayeredInstrumentRegistry(listOf(yaml, StandardInstrumentRegistry))

        // EURUSD isn't in yaml -> standard specs apply
        assertThat(layered.lookup("BACKTEST:EURUSD")!!.contractSize)
            .isEqualByComparingTo(BigDecimal("100000"))
    }

    @Test
    fun `unknown in every layer returns null`() {
        val layered = LayeredInstrumentRegistry(listOf(StandardInstrumentRegistry))
        assertThat(layered.lookup("BACKTEST:BTCUSDT")).isNull()
    }
}
