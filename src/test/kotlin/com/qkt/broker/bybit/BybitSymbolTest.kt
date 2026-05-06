package com.qkt.broker.bybit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitSymbolTest {
    @Test
    fun `splits BYBIT_SPOT prefix into category and bare symbol`() {
        val parsed = BybitSymbol.parse("BYBIT_SPOT:BTCUSDT")
        assertThat(parsed.category).isEqualTo("spot")
        assertThat(parsed.bare).isEqualTo("BTCUSDT")
    }

    @Test
    fun `splits BYBIT_LINEAR prefix into linear category`() {
        val parsed = BybitSymbol.parse("BYBIT_LINEAR:BTCUSDT")
        assertThat(parsed.category).isEqualTo("linear")
    }

    @Test
    fun `splits BYBIT_INVERSE prefix into inverse category`() {
        val parsed = BybitSymbol.parse("BYBIT_INVERSE:BTCUSD")
        assertThat(parsed.category).isEqualTo("inverse")
    }

    @Test
    fun `rejects symbol without a recognized Bybit prefix`() {
        assertThatThrownBy { BybitSymbol.parse("OANDA:EURUSD") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `re-prefix builds the qkt symbol from a native bare symbol and category`() {
        assertThat(BybitSymbol.toQkt(category = "spot", bare = "BTCUSDT")).isEqualTo("BYBIT_SPOT:BTCUSDT")
        assertThat(BybitSymbol.toQkt(category = "linear", bare = "ETHUSDT")).isEqualTo("BYBIT_LINEAR:ETHUSDT")
    }
}
