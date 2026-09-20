package com.qkt.observe.insights

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateBrokerFillTest {
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
                exitReason = ExitReason.STOP,
                timestamp = 1718000000000L,
                sequenceId = 7L,
            )
        val json = InsightsTranslate.fromOrderFilled(e).toJson("qkt-prod")
        assertThat(json).contains(""""v":1""")
        assertThat(json).contains(""""instanceId":"qkt-prod"""")
        assertThat(json).contains(""""type":"order.filled"""")
        assertThat(json).contains(""""price":2350.50""")
        assertThat(json).contains(""""qty":0.10""")
        assertThat(json).contains(""""exitReason":"STOP"""")
        assertThat(json).contains(""""strategyId":"latch"""")
        assertThat(json).doesNotContain(""""price":"2350.50"""")
    }

    @Test
    fun `broker deal has a deterministic id and ships the deal fields`() {
        val deal =
            com.qkt.broker.BrokerDeal(
                broker = "EXNESS",
                dealTicket = "456",
                positionTicket = "123",
                orderTicket = "789",
                symbol = "EXNESS:XAUUSD",
                side = Side.SELL,
                entry = "OUT",
                qty = BigDecimal("0.01"),
                price = BigDecimal("2310.2"),
                profit = BigDecimal("9.7"),
                commission = BigDecimal("-0.07"),
                swap = BigDecimal("-0.12"),
                magic = 10001,
                comment = "dsl-hedge_straddle",
                ts = 1781201000000L,
                fee = BigDecimal("-0.75"),
                clientOrderId = "qkt-abc-123",
            )
        val env = InsightsTranslate.brokerDeal(deal, strategyId = "hedge_straddle")
        assertThat(env.id).isEqualTo("deal-EXNESS-456")
        assertThat(env.seq).isEqualTo(0L)
        assertThat(env.ts).isEqualTo(1781201000000L)
        assertThat(env.type).isEqualTo("broker.deal")
        assertThat(env.strategyId).isEqualTo("hedge_straddle")
        val json = env.toJson("qkt-prod")
        assertThat(json).contains(""""dealTicket":"456"""")
        assertThat(json).contains(""""positionTicket":"123"""")
        assertThat(json).contains(""""orderTicket":"789"""")
        assertThat(json).contains(""""side":"SELL"""")
        assertThat(json).contains(""""entry":"OUT"""")
        assertThat(json).contains(""""price":2310.2""")
        assertThat(json).contains(""""profit":9.7""")
        assertThat(json).contains(""""commission":-0.07""")
        assertThat(json).contains(""""swap":-0.12""")
        assertThat(json).contains(""""fee":-0.75""")
        assertThat(json).contains(""""clientOrderId":"qkt-abc-123"""")
        assertThat(json).contains(""""magic":10001""")
        assertThat(json).contains(""""ts":1781201000000""")
        assertThat(json).contains(""""strategyId":"hedge_straddle"""")
    }

    @Test
    fun `unattributed broker deal omits the payload strategyId`() {
        val deal =
            com.qkt.broker.BrokerDeal(
                broker = "EXNESS",
                dealTicket = "457",
                positionTicket = null,
                orderTicket = null,
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                entry = "IN",
                qty = BigDecimal("0.01"),
                price = BigDecimal("2300.5"),
                profit = BigDecimal.ZERO,
                commission = BigDecimal.ZERO,
                swap = BigDecimal.ZERO,
                magic = null,
                comment = null,
                ts = 1781201000000L,
            )
        val env = InsightsTranslate.brokerDeal(deal, strategyId = null)
        assertThat(env.strategyId).isNull()
        val json = env.toJson("qkt-prod")
        assertThat(json).doesNotContain("strategyId")
        assertThat(json).doesNotContain("positionTicket")
        assertThat(json).doesNotContain("null")
    }

    @Test
    fun `partial fill preserves broker id side and costs`() {
        val env =
            InsightsTranslate.fromOrderPartiallyFilled(
                BrokerEvent.OrderPartiallyFilled(
                    clientOrderId = "o1",
                    brokerOrderId = "b1",
                    symbol = "XAUUSD",
                    side = Side.SELL,
                    price = BigDecimal("2351.25"),
                    quantity = BigDecimal("0.03"),
                    cumulativeFilled = BigDecimal("0.07"),
                    strategyId = "latch",
                    venueCosts = BigDecimal("0.12"),
                    timestamp = 1L,
                    sequenceId = 13L,
                ),
            )

        assertThat(env.payload).containsEntry("brokerOrderId", "b1")
        assertThat(env.payload).containsEntry("side", "SELL")
        assertThat(env.payload).containsEntry("venueCosts", BigDecimal("0.12"))
    }
}
