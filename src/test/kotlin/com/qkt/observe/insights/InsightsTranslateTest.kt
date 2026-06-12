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
    fun `state account maps the broker snapshot and omits absent margin fields`() {
        val full =
            com.qkt.broker.BrokerAccountState(
                broker = "EXNESS",
                currency = "USD",
                balance = BigDecimal("7824.05"),
                equity = BigDecimal("7676.54"),
                margin = BigDecimal("540.97"),
                marginFree = BigDecimal("7135.57"),
                openProfit = BigDecimal("-147.51"),
                marginLevel = BigDecimal("1419.03"),
            )
        val env = InsightsTranslate.stateAccount(ts = 1718000000000L, s = full)
        assertThat(env.id).isEqualTo("acct-EXNESS-1718000000000")
        assertThat(env.type).isEqualTo("state.account")
        assertThat(env.strategyId).isNull()
        val json = env.toJson("qkt-prod")
        assertThat(json).contains(""""broker":"EXNESS"""")
        assertThat(json).contains(""""currency":"USD"""")
        assertThat(json).contains(""""balance":7824.05""")
        assertThat(json).contains(""""equity":7676.54""")
        assertThat(json).contains(""""margin":540.97""")
        assertThat(json).contains(""""marginFree":7135.57""")
        assertThat(json).contains(""""openProfit":-147.51""")
        assertThat(json).contains(""""marginLevel":1419.03""")

        val bare = full.copy(margin = null, marginFree = null, openProfit = null, marginLevel = null)
        val bareJson = InsightsTranslate.stateAccount(ts = 1L, s = bare).toJson("qkt-prod")
        assertThat(bareJson).doesNotContain("margin")
        assertThat(bareJson).doesNotContain("openProfit")
        assertThat(bareJson).doesNotContain("null")
    }

    @Test
    fun `state positions carries each ticket and omits null optionals`() {
        val attributed =
            StatePosition(
                ticket = "123",
                symbol = "EXNESS:XAUUSD",
                side = "BUY",
                qty = BigDecimal("0.01"),
                entryPrice = BigDecimal("2300.5"),
                currentPrice = BigDecimal("2310.2"),
                profit = BigDecimal("9.7"),
                swap = BigDecimal("-0.12"),
                openedAt = 1781200000000L,
                strategyId = "hedge_straddle",
            )
        val orphan =
            attributed.copy(
                ticket = "124",
                currentPrice = null,
                profit = null,
                swap = null,
                openedAt = null,
                strategyId = null,
            )
        val env =
            InsightsTranslate.statePositions(
                ts = 1718000000000L,
                broker = "EXNESS",
                positions = listOf(attributed, orphan),
            )
        assertThat(env.id).isEqualTo("posn-EXNESS-1718000000000")
        assertThat(env.type).isEqualTo("state.positions")
        assertThat(env.strategyId).isNull()
        val json = env.toJson("qkt-prod")
        assertThat(json).contains(""""ticket":"123"""")
        assertThat(json).contains(""""side":"BUY"""")
        assertThat(json).contains(""""qty":0.01""")
        assertThat(json).contains(""""entryPrice":2300.5""")
        assertThat(json).contains(""""currentPrice":2310.2""")
        assertThat(json).contains(""""openedAt":1781200000000""")
        assertThat(json).contains(""""strategyId":"hedge_straddle"""")
        // The orphan ticket appears with its nulls absent, not serialized as null.
        assertThat(json).contains(""""ticket":"124"""")
        assertThat(json).doesNotContain("null")
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
}
