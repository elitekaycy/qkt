package com.qkt.observe.insights

import com.qkt.common.Side
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.SignalEvent
import com.qkt.events.SignalSuppressedEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderRequestEvidence
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateDecisionTest {
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
}
