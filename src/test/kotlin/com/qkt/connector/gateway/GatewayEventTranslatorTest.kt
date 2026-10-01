package com.qkt.connector.gateway

import com.qkt.accounting.CostKind
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GatewayEventTranslatorTest {
    private val code = "BTC_USDC-25DEC26-92000-C"
    private val symbol = "DERIBIT:BTC_USDC_25DEC26_92000_C"
    private val translator = GatewayEventTranslator(GatewaySymbols("DERIBIT:", listOf(code))) { "strat" }

    private fun order(
        status: String,
        filled: String = "0",
        reason: String? = null,
    ) = WireOrder(
        "c1",
        "9",
        code,
        "sell",
        "limit",
        "0.3",
        "650",
        null,
        "gtc",
        false,
        status,
        filled,
        null,
        reason,
        1,
        2,
    )

    private fun fill(
        id: String,
        quantity: String,
        costs: List<WireCost> = emptyList(),
    ) = WireFill("c1", "9", id, code, "sell", quantity, "651.5", 5, costs)

    @Test
    fun `an order is accepted once however many working updates arrive`() {
        val first = translator.order(order("working"))
        val again = translator.order(order("working"))

        assertThat(first).isEqualTo(BrokerEvent.OrderAccepted("c1", "9", "strat"))
        assertThat(again).isNull()
    }

    @Test
    fun `fills are partial until the order's quantity is reached, then the last one completes it`() {
        translator.order(order("working"))

        val partial = translator.fill(fill("f1", "0.1")) as BrokerEvent.OrderPartiallyFilled
        val done = translator.fill(fill("f2", "0.2")) as BrokerEvent.OrderFilled

        assertThat(partial.cumulativeFilled).isEqualByComparingTo("0.1")
        assertThat(partial.symbol).isEqualTo(symbol)
        assertThat(partial.side).isEqualTo(Side.SELL)
        assertThat(done.quantity).isEqualByComparingTo("0.2")
        assertThat(done.price).isEqualByComparingTo("651.5")
        assertThat(done.strategyId).isEqualTo("strat")
    }

    @Test
    fun `a replayed fill is booked once`() {
        translator.expect("c1", BigDecimal("0.3"))

        assertThat(translator.fill(fill("f1", "0.1"))).isNotNull()
        assertThat(translator.fill(fill("f1", "0.1"))).isNull()
    }

    @Test
    fun `typed costs carry over, and a settlement names the contract, its price and the account's costs`() {
        translator.expect("c1", BigDecimal("0.3"))

        val filled =
            translator.fill(fill("f1", "0.3", listOf(WireCost("commission", "0.5", "USDC")))) as BrokerEvent.OrderFilled
        val settled =
            translator.settlement(
                WireSettlement(code, "1000", 9, listOf(WireCost("delivery_fee", "1.25", "USDC"))),
            )

        assertThat(filled.typedVenueCosts.map { it.kind to it.amount.amount.toPlainString() })
            .containsExactly(CostKind.COMMISSION to "0.5")
        assertThat(settled.symbol).isEqualTo(symbol)
        assertThat(settled.price).isEqualByComparingTo("1000")
        assertThat(settled.costs.single().kind).isEqualTo(CostKind.EXCHANGE_FEE)
    }

    @Test
    fun `cancels and rejections end the order once, with the venue's reason`() {
        assertThat(translator.order(order("rejected", reason = "post-only would cross")))
            .isEqualTo(BrokerEvent.OrderRejected("c1", "9", "post-only would cross", "strat"))
        assertThat(translator.order(order("rejected", reason = "post-only would cross"))).isNull()
        val other = GatewayEventTranslator(GatewaySymbols("DERIBIT:", listOf(code))) { "strat" }
        assertThat(other.order(order("cancelled", filled = "0.1")))
            .isInstanceOf(BrokerEvent.OrderCancelled::class.java)
    }

    @Test
    fun `an unknown status, side, cost kind or instrument is a protocol error, never a guess`() {
        assertThatThrownBy { translator.order(order("expired")) }.isInstanceOf(GatewayProtocolException::class.java)
        assertThatThrownBy { translator.fill(fill("f1", "0.1").copy(side = "short")) }
            .isInstanceOf(GatewayProtocolException::class.java)
        assertThatThrownBy { translator.fill(fill("f2", "0.1", listOf(WireCost("rebate", "1", "USDC")))) }
            .isInstanceOf(GatewayProtocolException::class.java)
        assertThatThrownBy { translator.fill(fill("f3", "0.1").copy(symbol = "ETH-PERP")) }
            .isInstanceOf(GatewayProtocolException::class.java)
    }
}
