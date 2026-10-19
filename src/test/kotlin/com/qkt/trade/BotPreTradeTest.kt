package com.qkt.trade

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotPreTradeTest {
    private val ctx =
        BotQuoteContext(
            bid = BigDecimal("2650.0"),
            ask = BigDecimal("2650.5"),
            equity = BigDecimal("10000"),
            balance = BigDecimal("10000"),
            contractSize = BigDecimal("100"),
            accountCurrency = "USD",
            quoteCurrency = "USD",
            volumeMin = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.01"),
            volumeMax = null,
            digits = 2,
        )

    private fun market(qty: String) =
        CompiledBotOrder(
            OrderRequest.Market("bot-1", "EXNESS:XAUUSD", Side.BUY, BigDecimal(qty), TimeInForce.GTC, 1L, "a"),
            null,
            null,
        )

    private fun limit(price: String) =
        CompiledBotOrder(
            OrderRequest.Limit(
                "bot-1",
                "EXNESS:XAUUSD",
                Side.BUY,
                BigDecimal("0.1"),
                BigDecimal(price),
                TimeInForce.GTC,
                1L,
                "a",
            ),
            null,
            null,
        )

    private fun check(
        order: CompiledBotOrder,
        qty: String = "100",
        notional: String = "250000",
        collar: String = "0.25",
        context: BotQuoteContext = ctx,
    ) = checkBotPreTrade(order, context, BigDecimal(qty), BigDecimal(notional), BigDecimal(collar))

    @Test
    fun `qty above max_order_qty is refused`() {
        assertThatThrownBy { check(market("2"), qty = "1") }
            .hasMessageContaining("risk.max_order_qty")
    }

    @Test
    fun `notional above max_order_notional is refused`() {
        // 1 lot x 2650.5 x 100 = 265,050 > 250,000
        assertThatThrownBy { check(market("1")) }
            .hasMessageContaining("risk.max_order_notional")
            .hasMessageContaining("265050")
    }

    @Test
    fun `limit outside the collar is refused`() {
        assertThatThrownBy { check(limit("1800")) }.hasMessageContaining("risk.price_collar_pct")
    }

    @Test
    fun `an unknown contract size under a tightened notional cap is refused`() {
        assertThatThrownBy { check(market("0.1"), notional = "1000", context = ctx.copy(contractSize = null)) }
            .hasMessageContaining("notional cannot be checked")
    }

    @Test
    fun `the collar measures against the venue's reference price, not a broken book's mid`() {
        // A thin book (bid 61, ask 104.6) has a mid of 82.9; the venue's mark is 104.5.
        val thin = ctx.copy(bid = BigDecimal("61.164"), ask = BigDecimal("104.647"), reference = BigDecimal("104.5"))

        assertThatCode { check(limit("104"), context = thin) }.doesNotThrowAnyException()
        assertThatThrownBy { check(limit("60"), context = thin) }.hasMessageContaining("risk.price_collar_pct")
    }

    @Test
    fun `inside every limit passes`() {
        assertThatCode { check(market("0.5")) }.doesNotThrowAnyException()
        assertThatCode { check(limit("2600")) }.doesNotThrowAnyException()
        assertThatCode { check(market("0.5"), context = ctx.copy(contractSize = null)) }.doesNotThrowAnyException()
    }
}
