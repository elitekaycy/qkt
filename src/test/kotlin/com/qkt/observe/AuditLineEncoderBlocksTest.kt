package com.qkt.observe

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.SignalEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuditLineEncoderBlocksTest {
    private val accounted =
        FillAccountedEvent(
            orderId = "o-1",
            strategyId = "alpha",
            symbol = "XAUUSD",
            fillSliceId = "o-1:7",
            sourceFillSequenceId = 7L,
            cumulativeFilled = BigDecimal("0.30"),
            modeledCommissionAccount = BigDecimal("0.1"),
            venueCostsAccount = BigDecimal("0.2"),
            totalCostsAccount = BigDecimal("0.3"),
            accountNativeRealized = BigDecimal("2"),
            strategyNativeRealized = BigDecimal("2"),
            nativeCurrency = "EUR",
            grossAccountRealized = BigDecimal("2.2"),
            grossStrategyAccountRealized = BigDecimal("2.2"),
            accountCurrency = "USD",
            netAccountRealized = BigDecimal("1.9"),
            netStrategyAccountRealized = BigDecimal("1.9"),
            conversionRate = BigDecimal("1.1"),
            conversionTimestampMs = 99L,
            conversionSource = "ecb",
            contractSize = null,
            accountPositionBefore = Position("XAUUSD", BigDecimal("0.3"), BigDecimal("1990"), openedAt = 5L),
            accountPositionAfter = null,
            strategyPositionBefore = null,
            strategyPositionAfter = null,
            reducedExposure = true,
            legId = "leg-1",
            legAction = StrategyPositionTracker.LegAction.CLOSED,
            partial = true,
            timestamp = 100L,
            sequenceId = 3L,
        )

    @Test
    fun `an accounted fill line carries the cumulative quantity and conversion`() {
        val line = encodeAuditLine(accounted)
        assertThat(line)
            .contains("\"cumulativeFilled\":\"0.30\"")
            .contains("\"conversionRate\":\"1.1\",\"conversionTimestampMs\":99,\"conversionSource\":\"ecb\"")
            .contains("\"legId\":\"leg-1\",\"legAction\":\"CLOSED\"")
            .contains(
                "\"accountPositionBefore\":{\"symbol\":\"XAUUSD\",\"quantity\":\"0.3\",\"avgEntryPrice\":\"1990\",\"openedAtMs\":5}",
            ).contains("\"accountPositionAfter\":null")
            .doesNotContain("\"contractSize\":")
            .endsWith("}\n")
    }

    @Test
    fun `a partial fill line records the slice and the running total`() {
        val line =
            encodeAuditLine(
                BrokerEvent.OrderPartiallyFilled(
                    clientOrderId = "o-1",
                    brokerOrderId = null,
                    symbol = "XAUUSD",
                    side = Side.SELL,
                    price = BigDecimal("2001.5"),
                    quantity = BigDecimal("0.1"),
                    cumulativeFilled = BigDecimal("0.2"),
                    strategyId = "alpha",
                ),
            )
        assertThat(line).contains(
            ",\"fill\":{\"side\":\"SELL\",\"price\":\"2001.5\",\"quantity\":\"0.1\"," +
                "\"cumulativeFilled\":\"0.2\",\"brokerOrderId\":\"\",\"partial\":true}",
        )
    }

    @Test
    fun `a submit signal line carries the order block`() {
        val request = OrderRequest.Market("o-9", "XAUUSD", Side.BUY, BigDecimal("0.2"), TimeInForce.GTC, 1L, "alpha")

        val line = encodeAuditLine(SignalEvent(Signal.Submit(request), strategyId = "alpha"))

        assertThat(line).contains("\"orderSchemaVersion\":", "\"order\":{").contains("\"strategyId\":\"alpha\"")
    }

    @Test
    fun `json strings escape quotes backslashes and control characters`() {
        assertThat(jsonString("say \"hi\"\\\n\r\t!")).isEqualTo("\"say \\\"hi\\\"\\\\\\n\\r\\t!\"")
    }
}
