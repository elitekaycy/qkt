package com.qkt.pnl

import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.StandardInstrumentRegistry
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ContractFeeCommissionTest {
    private val es =
        FuturesRoot(
            "CME:ES",
            "USD",
            BigDecimal("50"),
            BigDecimal("0.25"),
            BigDecimal.ONE,
            BigDecimal.ONE,
            null,
            null,
            BigDecimal("1.29"),
            BigDecimal.ZERO,
            null,
        )
    private val btc =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal("0.0005"),
            null,
        )
    private val registry =
        LayeredInstrumentRegistry(
            listOf(ContractCatalogRegistry(listOf(es, btc), emptyMap()), StandardInstrumentRegistry),
        )
    private val model = ContractFeeCommission(registry, fallback = PerLotCommission(registry))

    @Test
    fun `per-contract fees scale with contracts on either side`() {
        assertThat(model.cost("CME:ES@front", BigDecimal("3"), BigDecimal("6500"))).isEqualByComparingTo("3.87")
        assertThat(model.cost("CME:ES@front", BigDecimal("-3"), BigDecimal("6500"))).isEqualByComparingTo("3.87")
    }

    @Test
    fun `notional fees use price and multiplier`() {
        // 0.5 BTC x 60,000 x 1 x 0.0005 = 15
        assertThat(
            model.cost("BINANCE_UM:BTCUSDT@front", BigDecimal("0.5"), BigDecimal("60000")),
        ).isEqualByComparingTo("15")
    }

    @Test
    fun `non-derivative symbols use the fallback model unchanged`() {
        assertThat(model.cost("BACKTEST:XAUUSD", BigDecimal("1"), BigDecimal("1900"))).isEqualByComparingTo("0")
    }

    @Test
    fun `a notional fee without a price is refused`() {
        assertThatThrownBy { model.cost("BINANCE_UM:BTCUSDT@front", BigDecimal("0.5")) }.hasMessageContaining("price")
    }
}
