package com.qkt.observe.insights

import com.qkt.accounting.ConvertedMoney
import com.qkt.accounting.FxConversion
import com.qkt.accounting.MoneyAmount
import com.qkt.broker.OrderModification
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Limit as AstLimit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.events.BrokerEvent
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.SignalEvent
import com.qkt.events.SignalSuppressedEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.At
import com.qkt.execution.ExitReason
import com.qkt.execution.ExpiryAction
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderRequestEvidence
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.StackPlan
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import com.qkt.marketdata.Candle
import com.qkt.positions.Position
import com.qkt.strategy.Signal
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateTest {
    @Test
    fun `rule decision preserves condition fingerprints and evaluated candle`() {
        val candle =
            Candle(
                symbol = "EXNESS:EURUSD",
                open = BigDecimal("1.1000"),
                high = BigDecimal("1.1020"),
                low = BigDecimal("1.0990"),
                close = BigDecimal("1.1010"),
                volume = BigDecimal.ZERO,
                startTime = 1_718_000_000_000L,
                endTime = 1_718_000_060_000L,
                bid = BigDecimal("1.1009"),
                ask = BigDecimal("1.1011"),
            )
        val env =
            InsightsTranslate.fromRuleDecision(
                RuleDecisionEvent(
                    strategyId = "alpha",
                    decisionId = "alpha:entry:1718000060000:abc",
                    ruleId = "entry#0",
                    strategyFingerprint = "strategy-sha",
                    ruleFingerprint = "rule-sha",
                    conditionFingerprint = "condition-sha",
                    conditionResult = true,
                    alias = "eur1",
                    broker = "exness",
                    timeframe = "1m",
                    signalCount = 1,
                    candle = candle,
                    timestamp = candle.endTime,
                    sequenceId = 16L,
                ),
            )

        assertThat(env.type).isEqualTo("decision.rule_evaluated")
        assertThat(env.strategyId).isEqualTo("alpha")
        assertThat(env.payload)
            .containsEntry("decisionId", "alpha:entry:1718000060000:abc")
            .containsEntry("ruleId", "entry#0")
            .containsEntry("conditionFingerprint", "condition-sha")
            .containsEntry("conditionResult", true)
            .containsEntry("signalCount", 1)
        assertThat(env.payload["candle"])
            .isEqualTo(
                mapOf(
                    "symbol" to "EXNESS:EURUSD",
                    "startTimeMs" to 1_718_000_000_000L,
                    "endTimeMs" to 1_718_000_060_000L,
                    "open" to BigDecimal("1.1000"),
                    "high" to BigDecimal("1.1020"),
                    "low" to BigDecimal("1.0990"),
                    "close" to BigDecimal("1.1010"),
                    "volume" to BigDecimal.ZERO,
                    "bid" to BigDecimal("1.1009"),
                    "ask" to BigDecimal("1.1011"),
                ),
            )
        assertThat(env.toJson("qkt-prod"))
            .contains("\"type\":\"decision.rule_evaluated\"")
            .contains("\"endTimeMs\":1718000060000")
    }

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
                strategyId = "latch",
            )
        val env = InsightsTranslate.fromTrade(e)
        assertThat(env.type).isEqualTo("trade")
        assertThat(env.strategyId).isEqualTo("latch")
        assertThat(env.seq).isEqualTo(42L)
        assertThat(env.id).isEqualTo("event-trade-latch-1718000000001-42")
        assertThat(env.payload["orderId"]).isEqualTo("o1")
        assertThat(env.payload["side"]).isEqualTo("BUY")
    }

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
    fun `trade closed exposes net account pnl as canonical and gross reconciliation fields`() {
        val trade =
            Trade(
                orderId = "o-close",
                symbol = "EXNESS:USDJPY",
                price = BigDecimal("155.25"),
                quantity = BigDecimal("1000"),
                side = Side.SELL,
                timestamp = 1718000000000L,
            )
        val converted =
            ConvertedMoney(
                native = MoneyAmount(BigDecimal("1550.00"), "JPY"),
                account = MoneyAmount(BigDecimal("10.00"), "USD"),
                conversion =
                    FxConversion(
                        from = "JPY",
                        to = "USD",
                        rate = BigDecimal("0.0064516129"),
                        timestamp = 1717999999000L,
                        source = "market",
                    ),
            )

        val env =
            InsightsTranslate.tradeClosed(
                trade = trade,
                netAccountRealized = BigDecimal("9.25"),
                strategyId = "alpha",
                convertedRealized = converted,
            )

        assertThat(env.type).isEqualTo("trade.closed")
        assertThat(env.strategyId).isEqualTo("alpha")
        assertThat(env.payload).containsEntry("realized", BigDecimal("9.25"))
        assertThat(env.payload).containsEntry("netAccountRealized", BigDecimal("9.25"))
        assertThat(env.payload).containsEntry("grossAccountRealized", BigDecimal("10.00"))
        assertThat(env.payload).containsEntry("accountRealized", BigDecimal("10.00"))
        assertThat(env.payload).containsEntry("costsAccount", BigDecimal("0.75"))
        assertThat(env.payload).containsEntry("nativeRealized", BigDecimal("1550.00"))
        assertThat(env.payload).containsEntry("nativeCurrency", "JPY")
        assertThat(env.payload).containsEntry("accountCurrency", "USD")
        assertThat(env.payload).containsEntry("currency", "USD")
        assertThat(env.payload).containsEntry("fxRate", BigDecimal("0.0064516129"))
        assertThat(env.payload).containsEntry("fxRateTimestamp", 1717999999000L)
        assertThat(env.payload).containsEntry("fxSource", "market")
        assertThat(env.payload).containsEntry("pnlBasis", "net_account_after_costs")
        assertThat(env.payload).containsEntry("realizedAliasOf", "netAccountRealized")
        assertThat(env.payload).containsEntry("sideAttribution", "fill_side")

        val json = env.toJson("qkt-prod")
        assertThat(json).contains(""""realized":9.25""")
        assertThat(json).contains(""""netAccountRealized":9.25""")
        assertThat(json).contains(""""grossAccountRealized":10.00""")
        assertThat(json).contains(""""costsAccount":0.75""")
        assertThat(json).contains(""""accountCurrency":"USD"""")
        assertThat(json).doesNotContain(""""realized":"9.25"""")
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
    fun `signal event includes strategy attribution and size`() {
        val env =
            InsightsTranslate.fromSignal(
                SignalEvent(
                    signal = Signal.Buy("XAUUSD", BigDecimal("0.25")),
                    strategyId = "latch",
                    timestamp = 1718000000000L,
                    sequenceId = 11L,
                ),
            )!!

        assertThat(env.type).isEqualTo("signal")
        assertThat(env.strategyId).isEqualTo("latch")
        assertThat(env.payload).containsEntry("intent", "BUY")
        assertThat(env.payload).containsEntry("symbol", "XAUUSD")
        assertThat(env.payload).containsEntry("side", "BUY")
        assertThat(env.payload["qty"]).isEqualTo(BigDecimal("0.25"))
    }

    @Test
    fun `submit signal approved order and risk rejection share canonical order evidence`() {
        val request =
            OrderRequest.StopLimit(
                id = "stop-limit",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.25"),
                stopPrice = BigDecimal("2355.00"),
                limitPrice = BigDecimal("2355.50"),
                timeInForce = TimeInForce.GTD,
                timestamp = 1_718_000_000_000L,
                strategyId = "latch",
                expiresAt = 1_718_000_060_000L,
            )
        val expected = OrderRequestEvidence.payload(request)
        val signal =
            InsightsTranslate.fromSignal(
                SignalEvent(Signal.Submit(request), strategyId = "latch", timestamp = 1L, sequenceId = 10L),
            )!!
        val approved = InsightsTranslate.fromOrderSubmit(OrderEvent(request, timestamp = 2L, sequenceId = 11L))
        val rejected =
            InsightsTranslate.fromRiskRejected(
                RiskRejectedEvent(request, reason = "max-notional", timestamp = 3L, sequenceId = 12L),
            )

        assertThat(signal.payload["order"]).isEqualTo(expected)
        assertThat(signal.payload).containsEntry("orderSchemaVersion", OrderRequestEvidence.SCHEMA_VERSION)
        assertThat(approved.payload)
            .containsAllEntriesOf(expected)
            .containsEntry("orderSchemaVersion", OrderRequestEvidence.SCHEMA_VERSION)
        assertThat(rejected.payload["order"]).isEqualTo(expected)
        assertThat(rejected.payload).containsEntry("orderSchemaVersion", OrderRequestEvidence.SCHEMA_VERSION)
        assertThat(rejected.payload).containsEntry("reason", "max-notional")
    }

    @Test
    fun `decision order link preserves rule signal and order correlation`() {
        val env =
            InsightsTranslate.fromDecisionOrderLinked(
                DecisionOrderLinkedEvent(
                    strategyId = "alpha",
                    decisionId = "alpha:gold:1718000000000:abc",
                    ruleId = "gold#0",
                    signalIndex = 2,
                    orderId = "o-42",
                    timestamp = 1_718_000_000_010L,
                    sequenceId = 17L,
                ),
            )

        assertThat(env.type).isEqualTo("decision.order_linked")
        assertThat(env.strategyId).isEqualTo("alpha")
        assertThat(env.seq).isEqualTo(17L)
        assertThat(env.payload)
            .containsEntry("decisionId", "alpha:gold:1718000000000:abc")
            .containsEntry("ruleId", "gold#0")
            .containsEntry("signalIndex", 2)
            .containsEntry("orderId", "o-42")
    }

    @Test
    fun `accounted fill exposes costs realized pnl positions and fill correlation`() {
        val before =
            Position(
                symbol = "EXNESS:XAUUSD",
                quantity = BigDecimal("0.10"),
                avgEntryPrice = BigDecimal("2350.00"),
                openedAt = 1_717_999_000_000L,
            )
        val after = before.copy(quantity = BigDecimal("0.04"))
        val env =
            InsightsTranslate.fromFillAccounted(
                FillAccountedEvent(
                    orderId = "o-close",
                    strategyId = "alpha",
                    symbol = "EXNESS:XAUUSD",
                    fillSliceId = "o-close:91",
                    sourceFillSequenceId = 91L,
                    cumulativeFilled = BigDecimal("0.06"),
                    modeledCommissionAccount = BigDecimal("0.10"),
                    venueCostsAccount = BigDecimal("0.20"),
                    totalCostsAccount = BigDecimal("0.30"),
                    accountNativeRealized = BigDecimal("12.00"),
                    strategyNativeRealized = BigDecimal("7.20"),
                    nativeCurrency = "USD",
                    grossAccountRealized = BigDecimal("12.00"),
                    grossStrategyAccountRealized = BigDecimal("7.20"),
                    accountCurrency = "USD",
                    netAccountRealized = BigDecimal("11.70"),
                    netStrategyAccountRealized = BigDecimal("6.90"),
                    conversionRate = BigDecimal.ONE,
                    conversionTimestampMs = 1_718_000_000_000L,
                    conversionSource = "identity",
                    contractSize = BigDecimal("100"),
                    accountPositionBefore = before,
                    accountPositionAfter = after,
                    strategyPositionBefore = before,
                    strategyPositionAfter = after,
                    reducedExposure = true,
                    partial = true,
                    timestamp = 1_718_000_000_100L,
                    sequenceId = 92L,
                ),
            )

        assertThat(env.type).isEqualTo("fill.accounted")
        assertThat(env.strategyId).isEqualTo("alpha")
        assertThat(env.payload)
            .containsEntry("orderId", "o-close")
            .containsEntry("fillSliceId", "o-close:91")
            .containsEntry("sourceFillSequenceId", 91L)
            .containsEntry("cumulativeFilled", BigDecimal("0.06"))
            .containsEntry("modeledCommissionAccount", BigDecimal("0.10"))
            .containsEntry("venueCostsAccount", BigDecimal("0.20"))
            .containsEntry("totalCostsAccount", BigDecimal("0.30"))
            .containsEntry("grossAccountRealized", BigDecimal("12.00"))
            .containsEntry("netAccountRealized", BigDecimal("11.70"))
            .containsEntry("netStrategyAccountRealized", BigDecimal("6.90"))
            .containsEntry("reducedExposure", true)
            .containsEntry("partial", true)
        assertThat(env.payload["accountPositionBefore"])
            .isEqualTo(
                mapOf(
                    "symbol" to "EXNESS:XAUUSD",
                    "quantity" to BigDecimal("0.10"),
                    "avgEntryPrice" to BigDecimal("2350.00"),
                    "openedAtMs" to 1_717_999_000_000L,
                ),
            )
        assertThat(env.payload["strategyPositionAfter"])
            .isEqualTo(
                mapOf(
                    "symbol" to "EXNESS:XAUUSD",
                    "quantity" to BigDecimal("0.04"),
                    "avgEntryPrice" to BigDecimal("2350.00"),
                    "openedAtMs" to 1_717_999_000_000L,
                ),
            )
        assertThat(env.toJson("qkt-prod"))
            .contains("\"sourceFillSequenceId\":91")
            .contains("\"totalCostsAccount\":0.30")
            .contains("\"accountPositionBefore\":{")
    }

    @Test
    fun `suppressed signal preserves reason and target`() {
        val env =
            InsightsTranslate.fromSignalSuppressed(
                SignalSuppressedEvent(
                    signal = Signal.Suppressed("XAUUSD", "resize quantized to zero"),
                    strategyId = "latch",
                    reason = "resize quantized to zero",
                    timestamp = 1718000000000L,
                    sequenceId = 12L,
                ),
            )

        assertThat(env.type).isEqualTo("signal.suppressed")
        assertThat(env.strategyId).isEqualTo("latch")
        assertThat(env.payload).containsEntry("symbol", "XAUUSD")
        assertThat(env.payload).containsEntry("reason", "resize quantized to zero")
    }

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
    }

    @Test
    fun `order submit payload covers every order request subtype`() {
        fun market(
            id: String = "m1",
            side: Side = Side.BUY,
        ) = OrderRequest.Market(
            id = id,
            symbol = "XAUUSD",
            side = side,
            quantity = BigDecimal("0.10"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1718000000000L,
            strategyId = "latch",
        )

        val requests =
            listOf(
                market().copy(closesTicket = "ticket-1", closesLegId = "leg-1", partialClose = true),
                OrderRequest.Limit(
                    "lim",
                    "XAUUSD",
                    Side.BUY,
                    BigDecimal("0.10"),
                    BigDecimal("2349.5"),
                    TimeInForce.GTD,
                    1L,
                    "latch",
                    2L,
                ),
                OrderRequest.Stop(
                    "stop",
                    "XAUUSD",
                    Side.BUY,
                    BigDecimal("0.10"),
                    BigDecimal("2355"),
                    TimeInForce.GTC,
                    1L,
                    "latch",
                ),
                OrderRequest.StopLimit(
                    "slim",
                    "XAUUSD",
                    Side.BUY,
                    BigDecimal("0.10"),
                    BigDecimal("2355"),
                    BigDecimal("2356"),
                    TimeInForce.GTC,
                    1L,
                    "latch",
                ),
                OrderRequest.IfTouched(
                    "touch",
                    "XAUUSD",
                    Side.SELL,
                    BigDecimal("0.10"),
                    BigDecimal("2360"),
                    TriggerType.LIMIT,
                    BigDecimal("2359"),
                    TimeInForce.GTC,
                    1L,
                    "latch",
                ),
                OrderRequest.TrailingStop(
                    "trail",
                    "XAUUSD",
                    Side.SELL,
                    BigDecimal("0.10"),
                    BigDecimal("10"),
                    TrailMode.ABSOLUTE,
                    TimeInForce.GTC,
                    1L,
                    "latch",
                ),
                OrderRequest.ArmedTrailingStop(
                    "armed",
                    "XAUUSD",
                    Side.SELL,
                    BigDecimal("0.10"),
                    BigDecimal("2350"),
                    BigDecimal("8"),
                    BigDecimal("12"),
                    TimeInForce.GTC,
                    1L,
                    "latch",
                ),
                OrderRequest.SteppedStop(
                    id = "stepped",
                    symbol = "XAUUSD",
                    side = Side.SELL,
                    quantity = BigDecimal("0.10"),
                    entryPrice = BigDecimal("2350"),
                    initialDistance = BigDecimal("10"),
                    steps =
                        listOf(
                            StopLossSpec.Step(BigDecimal("12"), BigDecimal.ZERO),
                            StopLossSpec.Step(BigDecimal("20"), BigDecimal("5")),
                        ),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "latch",
                ),
                OrderRequest.TimeTighteningStop(
                    id = "time-tightening",
                    symbol = "XAUUSD",
                    side = Side.SELL,
                    quantity = BigDecimal("0.10"),
                    entryPrice = BigDecimal("2350"),
                    initialDistance = BigDecimal("10"),
                    tightenBy = BigDecimal("2"),
                    intervalMs = 5_000L,
                    floorDistance = BigDecimal("4"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "latch",
                ),
                OrderRequest.TrailingStopLimit(
                    "tsl",
                    "XAUUSD",
                    Side.SELL,
                    BigDecimal("0.10"),
                    BigDecimal("1.5"),
                    TrailMode.PERCENT,
                    BigDecimal("0.2"),
                    TimeInForce.GTC,
                    1L,
                    "latch",
                ),
                OrderRequest.StandaloneOCO(
                    "oco",
                    "XAUUSD",
                    Side.BUY,
                    BigDecimal("0.10"),
                    market("oco-a"),
                    market("oco-b", Side.SELL),
                    TimeInForce.GTC,
                    1L,
                    "latch",
                ),
                OrderRequest.OTO(
                    "oto",
                    "XAUUSD",
                    Side.BUY,
                    BigDecimal("0.10"),
                    market("oto-parent"),
                    listOf(market("oto-child", Side.SELL)),
                    TimeInForce.GTC,
                    1L,
                    "latch",
                ),
                OrderRequest.Bracket(
                    id = "br",
                    symbol = "XAUUSD",
                    side = Side.BUY,
                    quantity = BigDecimal("0.10"),
                    entry = market("br-entry"),
                    takeProfit = BigDecimal("2360"),
                    stopLoss = StopLossSpec.ArmedTrail(BigDecimal("8"), BigDecimal("12")),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "latch",
                    takeProfitAst = ChildRr(NumLit(BigDecimal("2"))),
                    stopLossAst = ChildArmedTrail(NumLit(BigDecimal("8")), NumLit(BigDecimal("12"))),
                ),
                OrderRequest.ScaleOut(
                    id = "scale",
                    symbol = "XAUUSD",
                    side = Side.SELL,
                    quantity = BigDecimal("0.10"),
                    basis = market("scale-basis"),
                    legs = listOf(ScaleOutLeg(BigDecimal("2360"), BigDecimal("0.5"))),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "latch",
                ),
                OrderRequest.TimeExit(
                    id = "time",
                    symbol = "XAUUSD",
                    side = Side.SELL,
                    quantity = BigDecimal("0.10"),
                    target = market("time-target"),
                    deadline = Instant.ofEpochMilli(1718000060000L),
                    onExpiry = ExpiryAction.CLOSE_AT_MARKET,
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "latch",
                ),
                OrderRequest.Stack(
                    id = "stack",
                    symbol = "XAUUSD",
                    side = Side.BUY,
                    quantity = BigDecimal("0.30"),
                    plan =
                        StackPlan(
                            layers =
                                listOf(
                                    LayerSpec(
                                        0,
                                        SizeQty(NumLit(BigDecimal("0.10"))),
                                        com.qkt.dsl.ast.Market,
                                        Immediate,
                                        BigDecimal("0.10"),
                                    ),
                                    LayerSpec(
                                        1,
                                        SizeQty(NumLit(BigDecimal("0.20"))),
                                        AstLimit(NumLit(BigDecimal("2345"))),
                                        At(NumLit(BigDecimal("2345")), StackDirection.BELOW),
                                        BigDecimal("0.20"),
                                    ),
                                ),
                            outerBracket =
                                com.qkt.dsl.ast.BracketAst(
                                    takeProfit = ChildRr(NumLit(BigDecimal("2"))),
                                    stopLoss = ChildBy(NumLit(BigDecimal("10"))),
                                ),
                            withinMillis = 60_000L,
                        ),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "latch",
                ),
            )

        val byType =
            requests.associate { request ->
                request.javaClass.simpleName to
                    InsightsTranslate
                        .fromOrderSubmit(
                            OrderEvent(request, timestamp = 1L, sequenceId = request.id.hashCode().toLong()),
                        ).payload
            }

        assertThat(byType.keys)
            .containsExactlyInAnyOrder(
                "Market",
                "Limit",
                "Stop",
                "StopLimit",
                "IfTouched",
                "TrailingStop",
                "ArmedTrailingStop",
                "SteppedStop",
                "TimeTighteningStop",
                "TrailingStopLimit",
                "StandaloneOCO",
                "OTO",
                "Bracket",
                "ScaleOut",
                "TimeExit",
                "Stack",
            )
        assertThat(byType.getValue("Market")).containsEntry("closesTicket", "ticket-1")
        assertThat(byType.getValue("Market")).containsEntry("partialClose", true)
        assertThat(
            byType.getValue("Limit"),
        ).containsEntry("limitPrice", BigDecimal("2349.5")).containsEntry("expiresAt", 2L)
        assertThat(byType.getValue("Stop")).containsEntry("stopPrice", BigDecimal("2355"))
        assertThat(
            byType.getValue("StopLimit"),
        ).containsEntry("stopPrice", BigDecimal("2355")).containsEntry("limitPrice", BigDecimal("2356"))
        assertThat(
            byType.getValue("IfTouched"),
        ).containsEntry("onTrigger", "LIMIT").containsEntry("triggerPrice", BigDecimal("2360"))
        assertThat(
            byType.getValue("TrailingStop"),
        ).containsEntry("trailMode", "ABSOLUTE").containsEntry("trailAmount", BigDecimal("10"))
        assertThat(
            byType.getValue("ArmedTrailingStop"),
        ).containsEntry("entryPrice", BigDecimal("2350")).containsEntry("mfeThreshold", BigDecimal("12"))
        assertThat(byType.getValue("SteppedStop"))
            .containsEntry("initialDistance", BigDecimal("10"))
        assertThat(byType.getValue("SteppedStop")["steps"]).isInstanceOf(List::class.java)
        assertThat(byType.getValue("TimeTighteningStop"))
            .containsEntry("intervalMs", 5_000L)
            .containsEntry("floorDistance", BigDecimal("4"))
        assertThat(
            byType.getValue("TrailingStopLimit"),
        ).containsEntry("trailMode", "PERCENT").containsEntry("limitOffset", BigDecimal("0.2"))
        assertThat(byType.getValue("StandaloneOCO")["leg1"]).isInstanceOf(Map::class.java)
        assertThat(byType.getValue("OTO")["children"]).isInstanceOf(List::class.java)
        assertThat(byType.getValue("Bracket")["stopLossAst"]).isInstanceOf(Map::class.java)
        assertThat(byType.getValue("ScaleOut")["legs"]).isInstanceOf(List::class.java)
        assertThat(
            byType.getValue("TimeExit"),
        ).containsEntry("deadline", 1718000060000L).containsEntry("onExpiry", "CLOSE_AT_MARKET")
        assertThat(byType.getValue("Stack")["stackLayers"]).isInstanceOf(List::class.java)
        @Suppress("UNCHECKED_CAST")
        val stackLayers = byType.getValue("Stack")["stackLayers"] as List<Map<String, Any?>>
        assertThat(stackLayers).hasSize(2)
        assertThat(stackLayers[1]["trigger"]).isInstanceOf(Map::class.java)
        assertThat(byType.getValue("Stack")["outerBracket"]).isInstanceOf(Map::class.java)
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
