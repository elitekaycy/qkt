package com.qkt.observe.insights

import com.qkt.broker.OrderModification
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateOrderTest {
    @Test
    fun `order submit preserves bracket prices and child order metadata`() {
        val entry =
            OrderRequest.Market(
                id = "entry",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1718000000000L,
                strategyId = "latch",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "br1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry = entry,
                takeProfit = BigDecimal("2360.00"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("2340.00")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1718000000001L,
                strategyId = "latch",
                takeProfitAst = ChildRr(NumLit(BigDecimal("2"))),
                stopLossAst = ChildBy(NumLit(BigDecimal("10"))),
            )

        val env = InsightsTranslate.fromOrderSubmit(OrderEvent(bracket, timestamp = 1L, sequenceId = 12L))
        assertThat(env.payload).containsEntry("orderId", "entry")
        assertThat(env.payload).containsEntry("planOrderId", "br1")
        assertThat(env.payload).containsEntry("orderType", "Bracket")
        assertThat(env.payload).containsEntry("timeInForce", "GTC")
        assertThat(env.payload).containsEntry("strategyId", "latch")
        assertThat(env.payload).containsEntry("takeProfit", BigDecimal("2360.00"))
        assertThat(env.payload["entry"]).isInstanceOf(Map::class.java)
        assertThat(env.payload["stopLoss"]).isInstanceOf(Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val stop = env.payload["stopLoss"] as Map<String, Any?>
        assertThat(stop).containsEntry("type", "Fixed")
        assertThat(stop).containsEntry("price", BigDecimal("2340.00"))
        @Suppress("UNCHECKED_CAST")
        val tpAst = env.payload["takeProfitAst"] as Map<String, Any?>
        assertThat(tpAst).containsEntry("type", "Rr")
        @Suppress("UNCHECKED_CAST")
        val slAst = env.payload["stopLossAst"] as Map<String, Any?>
        assertThat(slAst).containsEntry("type", "By")
        assertThat(env.payload).doesNotContainKey("referencePrice")

        val priced =
            InsightsTranslate.fromOrderSubmit(
                OrderEvent(bracket, timestamp = 1L, sequenceId = 13L),
                referencePrice = BigDecimal("2350.25"),
            )
        assertThat(priced.payload).containsEntry("referencePrice", BigDecimal("2350.25"))
    }

    @Test
    fun `order modified preserves accepted change set`() {
        val env =
            InsightsTranslate.fromOrderModified(
                BrokerEvent.OrderModified(
                    clientOrderId = "o1",
                    brokerOrderId = "b1",
                    changes =
                        OrderModification(
                            newQuantity = BigDecimal("0.20"),
                            newLimitPrice = BigDecimal("2355.50"),
                        ),
                    strategyId = "latch",
                    timestamp = 1L,
                    sequenceId = 14L,
                ),
            )

        assertThat(env.type).isEqualTo("order.modified")
        assertThat(env.strategyId).isEqualTo("latch")
        assertThat(env.payload).containsEntry("orderId", "o1")
        assertThat(env.payload).containsEntry("brokerOrderId", "b1")
        @Suppress("UNCHECKED_CAST")
        val changes = env.payload["changes"] as Map<String, Any?>
        assertThat(changes).containsEntry("newQuantity", BigDecimal("0.20"))
        assertThat(changes).containsEntry("newLimitPrice", BigDecimal("2355.50"))
        assertThat(env.toJson("qkt-prod")).contains(""""newQuantity":0.20""")
        assertThat(env.toJson("qkt-prod")).contains(""""newLimitPrice":2355.50""")
        assertThat(env.toJson("qkt-prod")).doesNotContain("newStopPrice")
    }
}
