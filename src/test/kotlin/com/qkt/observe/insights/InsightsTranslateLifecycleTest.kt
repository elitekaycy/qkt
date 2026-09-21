package com.qkt.observe.insights

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateLifecycleTest {
    @Test
    fun `event ids do not collide across strategy sessions sharing a bus sequence`() {
        val first =
            InsightsTranslate.fromTrade(
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
                    strategyId = "first",
                ),
            )
        val second =
            InsightsTranslate.fromTrade(
                TradeEvent(
                    trade =
                        Trade(
                            orderId = "o2",
                            symbol = "XAUUSD",
                            price = BigDecimal("2350.5"),
                            quantity = BigDecimal("0.1"),
                            side = Side.BUY,
                            timestamp = 1718000000000L,
                        ),
                    timestamp = 1718000000001L,
                    sequenceId = 42L,
                    strategyId = "second",
                ),
            )

        assertThat(first.id).isNotEqualTo(second.id)
    }

    @Test
    fun `global risk events omit blank strategy attribution`() {
        val halted =
            RiskEvent.Halted(
                reason = "operator",
                strategyId = null,
                timestamp = 1718000000000L,
                sequenceId = 9L,
            )
        val resumed =
            RiskEvent.Resumed(
                strategyId = null,
                timestamp = 1718000001000L,
                sequenceId = 10L,
            )

        val haltedEnv = InsightsTranslate.fromRiskHalted(halted)
        val haltedJson = haltedEnv.toJson("qkt-prod")
        val resumedJson = InsightsTranslate.fromRiskResumed(resumed).toJson("qkt-prod")

        assertThat(haltedEnv.strategyId).isNull()
        assertThat(haltedJson).contains(""""type":"risk.halted""")
        assertThat(haltedJson).doesNotContain("strategyId")
        assertThat(resumedJson).contains(""""type":"risk.resumed""")
        assertThat(resumedJson).doesNotContain("strategyId")
    }

    @Test
    fun `a halt tells the dashboard its scope and whether only resume clears it`() {
        val persistent =
            com.qkt.events.RiskEvent
                .Halted("operator", "gold_trend", scope = "PERSISTENT", timestamp = 1L)
        val daily =
            com.qkt.events.RiskEvent
                .Halted("DailyLoss", "gold_trend", scope = "DAILY", timestamp = 1L)

        assertThat(InsightsTranslate.fromRiskHalted(persistent).toJson("qkt-prod"))
            .contains(""""scope":"PERSISTENT"""")
            .contains(""""persistent":true""")
        assertThat(InsightsTranslate.fromRiskHalted(daily).toJson("qkt-prod"))
            .contains(""""scope":"DAILY"""")
            .contains(""""persistent":false""")
    }

    @Test
    fun `strategy lifecycle events use deterministic ids and strategy attribution`() {
        val started =
            InsightsTranslate.strategyStarted(
                strategyId = "hedge_straddle",
                ts = 1718000000000L,
                metadata =
                    mapOf(
                        "sourcePath" to "/srv/qkt/strategies/hedge.qkt",
                        "dslVersion" to 1,
                        "symbols" to listOf("EXNESS:XAUUSD"),
                    ),
            )
        val stopped =
            InsightsTranslate.strategyStopped(
                strategyId = "hedge_straddle",
                ts = 1718000005000L,
                flatten = false,
            )

        assertThat(started.id).isEqualTo("strategy-started-hedge_straddle-1718000000000")
        assertThat(started.seq).isEqualTo(0L)
        assertThat(started.type).isEqualTo("strategy.started")
        assertThat(started.strategyId).isEqualTo("hedge_straddle")
        assertThat(started.toJson("qkt-prod")).contains(""""strategyId":"hedge_straddle"""")
        assertThat(started.toJson("qkt-prod")).contains(""""sourcePath":"/srv/qkt/strategies/hedge.qkt"""")
        assertThat(started.toJson("qkt-prod")).contains(""""dslVersion":1""")
        assertThat(started.toJson("qkt-prod")).contains(""""symbols":["EXNESS:XAUUSD"]""")
        assertThat(stopped.id).isEqualTo("strategy-stopped-hedge_straddle-1718000005000")
        assertThat(stopped.type).isEqualTo("strategy.stopped")
        assertThat(stopped.toJson("qkt-prod")).contains("\"flatten\":false")
    }

    @Test
    fun `portfolio telemetry preserves book identity and aggregated values`() {
        val configured =
            InsightsTranslate.portfolioConfigured(
                "book",
                1000L,
                BigDecimal("10000"),
            )
        val allocation =
            InsightsTranslate.portfolioAllocationUpdated(
                "book",
                1001L,
                mapOf("book:a" to BigDecimal("6000"), "book:b" to BigDecimal("4000")),
            )
        val equity =
            InsightsTranslate.portfolioEquityUpdated(
                "book",
                1002L,
                BigDecimal("10025"),
                BigDecimal("20"),
                BigDecimal("5"),
                mapOf("book:a" to BigDecimal("15"), "book:b" to BigDecimal("10")),
            )

        assertThat(configured.type).isEqualTo("portfolio.configured")
        assertThat(configured.strategyId).isNull()
        assertThat(configured.toJson("qkt-prod")).contains("\"capital\":10000")
        assertThat(allocation.toJson("qkt-prod")).contains("\"book:a\":6000")
        assertThat(equity.toJson("qkt-prod"))
            .contains("\"equity\":10025")
            .contains("\"perStrategy\"")
    }

    @Test
    fun `gateway unreachable folds broker and failure count into detail`() {
        val e = BrokerEvent.GatewayUnreachable(broker = "mt5", consecutiveFailures = 3, timestamp = 1L, sequenceId = 9L)
        val env = InsightsTranslate.fromGatewayUnreachable(e)
        assertThat(env.payload["detail"].toString()).contains("mt5").contains("3")
    }

    @Test
    fun `broker and marketdata lifecycle envelopes carry source health context`() {
        val down =
            InsightsTranslate.fromBrokerGatewayUnreachable(
                BrokerEvent.GatewayUnreachable(
                    broker = "EXNESS",
                    consecutiveFailures = 3,
                    timestamp = 1718000000000L,
                    sequenceId = 41L,
                ),
            )
        assertThat(down.type).isEqualTo("broker.disconnected")
        assertThat(down.payload).containsEntry("broker", "EXNESS")
        assertThat(down.payload).containsEntry("consecutiveFailures", 3)

        val recovered =
            InsightsTranslate.fromBrokerConnectionChanged(
                BrokerEvent.ConnectionChanged(
                    broker = "EXNESS",
                    state = BrokerEvent.ConnectionState.RECONNECTED,
                    reason = "gateway-recovered",
                    consecutiveFailures = 4,
                    timestamp = 1718000000100L,
                    sequenceId = 42L,
                ),
            )
        assertThat(recovered.type).isEqualTo("broker.reconnected")
        assertThat(recovered.payload).containsEntry("reason", "gateway-recovered")

        val connected = InsightsTranslate.brokerConnected("paper", 1718000000200L)
        assertThat(connected.type).isEqualTo("broker.connected")
        assertThat(connected.payload).containsEntry("state", "connected")

        val md = InsightsTranslate.marketDataReconnected("tradingview", listOf("XAUUSD"), 1718000000300L)
        assertThat(md.type).isEqualTo("marketdata.reconnected")
        assertThat(md.payload["symbols"]).isEqualTo(listOf("XAUUSD"))

        val stale = InsightsTranslate.marketDataStale("mt5", "EXNESS:XAUUSD", 1718000000400L, "quote age")
        assertThat(stale.type).isEqualTo("marketdata.stale")
        assertThat(stale.payload).containsEntry("state", "stale")
        assertThat(stale.payload["symbols"]).isEqualTo(listOf("EXNESS:XAUUSD"))
    }
}
