package com.qkt.observe.insights

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateBrokerStateTest {
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
                login = 435898347L,
                server = "Exness-MT5Trial9",
                name = "qkt-hedge-straddle",
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
        assertThat(json).contains(""""login":"435898347"""")
        assertThat(json).contains(""""server":"Exness-MT5Trial9"""")
        assertThat(json).contains(""""name":"qkt-hedge-straddle"""")

        val bare =
            full.copy(
                margin = null,
                marginFree = null,
                openProfit = null,
                marginLevel = null,
                login = 0,
                server = "",
                name = "",
            )
        val bareJson = InsightsTranslate.stateAccount(ts = 1L, s = bare).toJson("qkt-prod")
        assertThat(bareJson).doesNotContain("margin")
        assertThat(bareJson).doesNotContain("openProfit")
        assertThat(bareJson).doesNotContain(""""login"""")
        assertThat(bareJson).doesNotContain(""""server"""")
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
                stopLoss = BigDecimal("2290.0"),
                takeProfit = BigDecimal("2350.5"),
                requestedStopLoss = BigDecimal("2291.0"),
                requestedTakeProfit = BigDecimal("2350.5"),
                magic = 10001,
                clientOrderId = "qkt-abc-123",
            )
        val orphan =
            attributed.copy(
                ticket = "124",
                currentPrice = null,
                profit = null,
                swap = null,
                openedAt = null,
                strategyId = null,
                stopLoss = null,
                takeProfit = null,
                requestedStopLoss = null,
                requestedTakeProfit = null,
                magic = null,
                clientOrderId = null,
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
        assertThat(json).contains(""""stopLoss":2290.0""")
        assertThat(json).contains(""""takeProfit":2350.5""")
        assertThat(json).contains(""""requestedStopLoss":2291.0""")
        assertThat(json).contains(""""magic":10001""")
        assertThat(json).contains(""""clientOrderId":"qkt-abc-123"""")
        // The orphan ticket appears with its nulls absent, not serialized as null.
        assertThat(json).contains(""""ticket":"124"""")
        assertThat(json).doesNotContain("null")
    }

    @Test
    fun `state orders carries each resting order and omits null optionals`() {
        val order =
            StatePendingOrder(
                ticket = "501",
                symbol = "EXNESS:XAUUSD",
                side = "BUY",
                orderType = "ORDER_TYPE_BUY_LIMIT",
                qty = BigDecimal("0.01"),
                price = BigDecimal("2250.0"),
                stopLoss = BigDecimal("2200.0"),
                takeProfit = BigDecimal("2400.0"),
                expiresAt = 1781300000000L,
                createdAt = 1781200000000L,
                magic = 10001,
                clientOrderId = "qkt-ord-2",
                strategyId = "hedge_straddle",
            )
        val bare =
            order.copy(
                ticket = "502",
                price = null,
                stopLoss = null,
                takeProfit = null,
                expiresAt = null,
                createdAt = null,
                magic = null,
                clientOrderId = null,
                strategyId = null,
            )
        val env = InsightsTranslate.stateOrders(ts = 1718000000000L, broker = "EXNESS", orders = listOf(order, bare))
        assertThat(env.id).isEqualTo("pord-EXNESS-1718000000000")
        assertThat(env.type).isEqualTo("state.orders")
        assertThat(env.strategyId).isNull()
        val json = env.toJson("qkt-prod")
        assertThat(json).contains(""""ticket":"501"""")
        assertThat(json).contains(""""orderType":"ORDER_TYPE_BUY_LIMIT"""")
        assertThat(json).contains(""""price":2250.0""")
        assertThat(json).contains(""""stopLoss":2200.0""")
        assertThat(json).contains(""""expiresAt":1781300000000""")
        assertThat(json).contains(""""strategyId":"hedge_straddle"""")
        assertThat(json).contains(""""ticket":"502"""")
        assertThat(json).doesNotContain("null")
    }

    @Test
    fun `state persistence carries durability counters`() {
        val env =
            InsightsTranslate.statePersistence(
                ts = 1718000000000L,
                strategyId = "alpha",
                health =
                    com.qkt.persistence.PersistenceHealth(
                        enabled = true,
                        totalWrites = 10L,
                        slowWrites = 2L,
                        failedWrites = 1L,
                        consecutiveFailures = 1L,
                        failureEpisodes = 1L,
                        queueSize = 7,
                        callerRunsTotal = 3L,
                    ),
            )

        assertThat(env.type).isEqualTo("state.persistence")
        assertThat(env.strategyId).isEqualTo("alpha")
        assertThat(env.payload).containsEntry("failedWrites", 1L)
        assertThat(env.payload).containsEntry("callerRunsTotal", 3L)
        assertThat(env.toJson("qkt-prod")).contains(""""queueSize":7""")
    }
}
