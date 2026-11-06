package com.qkt.pnl

import com.qkt.common.Side
import com.qkt.instrument.EventContracts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.ProbabilityFee
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Kalshi's taker fee shape: 7% × contracts × p × (1 − p), rounded up to the cent once per order. */
class ProbabilityFeeCommissionTest {
    private val kalshi = ProbabilityFee(BigDecimal("0.07"), BigDecimal("0.0175"), BigDecimal("0.01"))
    private val unknownFee = "KALSHI:KXINXU_26NOV06H1500_T7450"
    private val eurusd =
        InstrumentMeta(
            qktSymbol = "EXNESS:EURUSD",
            contractSize = BigDecimal("100000"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.00001"),
            digits = 5,
            tradeStopsLevelPoints = 0,
            commissionPerLot = BigDecimal("7"),
        )
    private val registry =
        EventContracts.registry(
            EventContracts.meta(terms = EventContracts.terms(fee = kalshi)),
            EventContracts.meta(symbol = unknownFee),
            eurusd,
        )
    private val model = ProbabilityFeeCommission(registry, PerLotCommission(registry))

    private fun fill(
        qty: String,
        price: String,
        order: String = "ORD-1",
        symbol: String = EventContracts.SYMBOL,
        side: Side = Side.BUY,
    ) = model.cost(symbol, side, BigDecimal(qty), BigDecimal(price), order)

    @Test
    fun `ten contracts at 0_50 pay 0_175 rounded up to the cent`() {
        assertThat(fill("10", "0.50")).isEqualByComparingTo("0.18")
    }

    @Test
    fun `partial fills pay what the order's rounded fee grew by, so the rounding is paid once`() {
        assertThat(fill("4", "0.50")).isEqualByComparingTo("0.07")
        assertThat(fill("6", "0.50")).isEqualByComparingTo("0.11")
    }

    @Test
    fun `a second order is rounded on its own`() {
        assertThat(fill("10", "0.50", order = "ORD-1")).isEqualByComparingTo("0.18")
        assertThat(fill("10", "0.50", order = "ORD-2", side = Side.SELL)).isEqualByComparingTo("0.18")
    }

    @Test
    fun `a tiny fee near certainty still rounds up to one cent`() {
        assertThat(fill("1", "0.99")).isEqualByComparingTo("0.01")
    }

    @Test
    fun `a payout other than one scales the price to a probability`() {
        val terms = EventContracts.terms(payout = BigDecimal("100"), fee = kalshi)
        val dollar = EventContracts.registry(EventContracts.meta(terms = terms))
        val cost =
            ProbabilityFeeCommission(dollar, NoCommission)
                .cost(EventContracts.SYMBOL, Side.BUY, BigDecimal(10), BigDecimal(50), "O")
        // 0.07 × 10 × 50 × 50 / 100 = 17.50
        assertThat(cost).isEqualByComparingTo("17.50")
    }

    @Test
    fun `an event contract whose fee is unknown is charged by the fallback`() {
        assertThat(fill("10", "0.50", symbol = unknownFee)).isEqualByComparingTo("0")
        assertThat(model.cost(unknownFee, BigDecimal(10))).isEqualByComparingTo("0")
        assertThat(model.cost(unknownFee, BigDecimal(10), BigDecimal("0.5"))).isEqualByComparingTo("0")
    }

    @Test
    fun `any other instrument is charged exactly what the fallback charges`() {
        val fallback = PerLotCommission(registry)
        assertThat(fill("0.5", "1.1", symbol = "EXNESS:EURUSD"))
            .isEqualTo(fallback.cost("EXNESS:EURUSD", Side.BUY, BigDecimal("0.5"), BigDecimal("1.1"), "ORD-1"))
        assertThat(model.cost("EXNESS:EURUSD", BigDecimal("0.5")))
            .isEqualTo(fallback.cost("EXNESS:EURUSD", BigDecimal("0.5")))
        assertThat(model.cost("EXNESS:EURUSD", BigDecimal("0.5"), BigDecimal("1.1")))
            .isEqualTo(fallback.cost("EXNESS:EURUSD", BigDecimal("0.5"), BigDecimal("1.1")))
        assertThat(fill("1", "1", symbol = "UNKNOWN:X")).isEqualByComparingTo("0")
    }

    @Test
    fun `an event fee is never charged without the fill's side and order`() {
        assertThatThrownBy { model.cost(EventContracts.SYMBOL, BigDecimal(10)) }
            .hasMessageContaining("needs the fill's side and order")
        assertThatThrownBy { model.cost(EventContracts.SYMBOL, BigDecimal(10), BigDecimal("0.5")) }
            .hasMessageContaining("needs the fill's side and order")
    }
}
