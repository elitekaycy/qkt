package com.qkt.observe.insights

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateTest {
    @Test
    fun `trade event maps to the contract trade payload`() {
        val e =
            TradeEvent(
                trade =
                    Trade(
                        orderId = "o1",
                        symbol = "XAUUSD",
                        price = BigDecimal("2350.5"),
                        quantity = BigDecimal("0.1"),
                        side = Side.BUY,
                        timestamp = 1718000000000L,
                    ),
                timestamp = 1718000000001L,
                sequenceId = 42L,
            )
        val env = InsightsTranslate.fromTrade(e)
        assertThat(env.type).isEqualTo("trade")
        assertThat(env.seq).isEqualTo(42L)
        assertThat(env.id).isEqualTo("e42")
        assertThat(env.payload["orderId"]).isEqualTo("o1")
        assertThat(env.payload["side"]).isEqualTo("BUY")
    }

    @Test
    fun `order filled renders to valid contract json with numeric prices`() {
        val e =
            BrokerEvent.OrderFilled(
                clientOrderId = "o1",
                brokerOrderId = "b1",
                symbol = "XAUUSD",
                side = Side.BUY,
                price = BigDecimal("2350.50"),
                quantity = BigDecimal("0.10"),
                strategyId = "latch",
                venueCosts = BigDecimal("0.02"),
                timestamp = 1718000000000L,
                sequenceId = 7L,
            )
        val json = InsightsTranslate.fromOrderFilled(e).toJson("qkt-prod")
        assertThat(json).contains(""""v":1""")
        assertThat(json).contains(""""instanceId":"qkt-prod"""")
        assertThat(json).contains(""""type":"order.filled"""")
        assertThat(json).contains(""""price":2350.50""")
        assertThat(json).contains(""""qty":0.10""")
        assertThat(json).contains(""""strategyId":"latch"""")
        assertThat(json).doesNotContain(""""price":"2350.50"""")
    }

    @Test
    fun `gateway unreachable folds broker and failure count into detail`() {
        val e = BrokerEvent.GatewayUnreachable(broker = "mt5", consecutiveFailures = 3, timestamp = 1L, sequenceId = 9L)
        val env = InsightsTranslate.fromGatewayUnreachable(e)
        assertThat(env.payload["detail"].toString()).contains("mt5").contains("3")
    }

    @Test
    fun `equity snapshot carries pnl fields and a deterministic id`() {
        val env =
            InsightsTranslate.equitySnapshot(
                ts = 1718000000000L,
                strategyId = "latch",
                realized = BigDecimal("10"),
                unrealized = BigDecimal("-2"),
                equity = BigDecimal("1008"),
                startingBalance = BigDecimal("1000"),
            )
        assertThat(env.id).isEqualTo("eq-latch-1718000000000")
        assertThat(env.type).isEqualTo("snapshot.equity")
        val json = env.toJson("qkt-prod")
        assertThat(json).contains(""""equity":1008""")
        assertThat(json).contains(""""startingBalance":1000""")
    }
}
