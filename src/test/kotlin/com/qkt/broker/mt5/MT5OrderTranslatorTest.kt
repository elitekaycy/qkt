package com.qkt.broker.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5OrderTranslatorTest {
    private val translator =
        MT5OrderTranslator(
            profile = MT5DefaultProfiles.exness,
            symbol = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy),
        )

    private fun marketReq(side: Side) =
        OrderRequest.Market(
            id = "ord-1",
            symbol = "EURUSD",
            side = side,
            quantity = BigDecimal("0.1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s1",
        )

    @Test
    fun `BUY market translates to BUY type with suffixed symbol`() {
        val mt5 = translator.translate(marketReq(Side.BUY))
        assertThat(mt5.symbol).isEqualTo("EURUSDm")
        assertThat(mt5.type).isEqualTo("BUY")
        assertThat(mt5.volume).isEqualByComparingTo("0.1")
        assertThat(mt5.sl).isNull()
        assertThat(mt5.tp).isNull()
        assertThat(mt5.magic).isEqualTo(10001)
        assertThat(mt5.comment).isEqualTo("ord-1")
    }

    @Test
    fun `SELL market translates to SELL type`() {
        val mt5 = translator.translate(marketReq(Side.SELL))
        assertThat(mt5.type).isEqualTo("SELL")
    }

    @Test
    fun `Bracket with sl and tp translates with both fields`() {
        val entry = marketReq(Side.BUY)
        val bracket =
            OrderRequest.Bracket(
                id = "br-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entry = entry,
                takeProfit = BigDecimal("1.1500"),
                stopLoss = BigDecimal("1.0500"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val mt5 = translator.translate(bracket)
        assertThat(mt5.type).isEqualTo("BUY")
        assertThat(mt5.symbol).isEqualTo("EURUSDm")
        assertThat(mt5.sl).isEqualByComparingTo("1.0500")
        assertThat(mt5.tp).isEqualByComparingTo("1.1500")
        assertThat(mt5.comment).isEqualTo("br-1")
    }

    @Test
    fun `Limit translation throws so OrderManager falls back`() {
        val limit =
            OrderRequest.Limit(
                id = "l-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                limitPrice = BigDecimal("1.1000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        assertThatThrownBy { translator.translate(limit) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not natively translate")
    }
}
