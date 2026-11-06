package com.qkt.backtest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarFillsTest {
    @Test
    fun `a continuous stream synthesized from bars covers the contracts of its root`() {
        val fills = BarFills(setOf("BINANCE_UM:BTCUSDT@front", "EXNESS:BTCUSD"))

        assertThat(fills.any).isTrue()
        assertThat(fills.at("EXNESS:BTCUSD")).isTrue()
        assertThat(fills.at("BINANCE_UM:BTCUSDT_240927")).isTrue()
        assertThat(fills.at("BINANCE_UM:BTCUSDT_240927")).isTrue()
        assertThat(fills.at("BINANCE_UM:ETHUSDT_240927")).isFalse()
    }

    @Test
    fun `no synthesized symbol fills at the tick`() {
        assertThat(BarFills.NONE.any).isFalse()
        assertThat(BarFills.NONE.at("EXNESS:XAUUSD")).isFalse()
    }
}
