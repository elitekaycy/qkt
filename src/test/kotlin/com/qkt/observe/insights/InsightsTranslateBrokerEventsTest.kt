package com.qkt.observe.insights

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.app.LatchManagerFixture
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.SignalEvent
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateBrokerEventsTest {
    @Test
    fun `a position reconciliation reports before and after with a zero default`() {
        val env =
            InsightsTranslate.fromPositionReconciled(
                BrokerEvent.PositionReconciled(
                    symbol = "XAUUSD",
                    oldQty = null,
                    newQty = BigDecimal("0.3"),
                    oldAvgPx = null,
                    newAvgPx = BigDecimal("4700"),
                    source = "startup",
                    reason = "venue position",
                    timestamp = 5L,
                    sequenceId = 2L,
                ),
            )
        assertThat(env.id).isEqualTo("event-position.reconciled--5-2")
        assertThat(env.strategyId).isNull()
        assertThat(env.payload)
            .containsEntry("before", BigDecimal.ZERO)
            .containsEntry("after", BigDecimal("0.3"))
            .containsEntry("newAvgPx", BigDecimal("4700"))
            .containsEntry("reason", "venue position")
    }

    @Test
    fun `a balance update carries every currency and its source`() {
        val env =
            InsightsTranslate.fromBalancesUpdated(
                BrokerEvent.BalancesUpdated(mapOf("USD" to BigDecimal("100")), "poll", timestamp = 9L),
            )
        assertThat(env.type).isEqualTo("balances.updated")
        assertThat(env.payload).containsEntry("balances", mapOf("USD" to BigDecimal("100")))
        assertThat(env.payload).containsEntry("source", "poll")
    }

    @Test
    fun `connection changes map each state to its broker event type`() {
        fun typeOf(state: BrokerEvent.ConnectionState) =
            InsightsTranslate
                .fromBrokerConnectionChanged(
                    BrokerEvent.ConnectionChanged("EXNESS", state, reason = "r"),
                    mapOf("venue" to "mt5"),
                ).let { it.type to it.payload["state"] }

        assertThat(typeOf(BrokerEvent.ConnectionState.CONNECTED)).isEqualTo("broker.connected" to "connected")
        assertThat(typeOf(BrokerEvent.ConnectionState.DISCONNECTED))
            .isEqualTo("broker.disconnected" to "disconnected")
    }

    @Test
    fun `cancelled and rejected orders keep their ids and reason`() {
        val cancelled =
            InsightsTranslate.fromOrderCancelled(
                BrokerEvent.OrderCancelled("c1", "b1", "expired", strategyId = "s", timestamp = 1L),
            )
        val rejected =
            InsightsTranslate.fromOrderRejected(
                BrokerEvent.OrderRejected("c2", null, "no money", strategyId = "s", timestamp = 2L),
            )
        assertThat(cancelled.type).isEqualTo("order.cancelled")
        assertThat(cancelled.payload).containsEntry("orderId", "c1").containsEntry("reason", "expired")
        assertThat(rejected.type).isEqualTo("order.rejected")
        assertThat(rejected.payload).containsEntry("brokerOrderId", null).containsEntry("reason", "no money")
    }

    @Test
    fun `a fill lists its typed venue costs with normalised currency`() {
        val env =
            InsightsTranslate.fromOrderFilled(
                BrokerEvent.OrderFilled(
                    clientOrderId = "c1",
                    brokerOrderId = "b1",
                    symbol = "XAUUSD",
                    side = Side.BUY,
                    price = BigDecimal("4700"),
                    quantity = BigDecimal("0.1"),
                    typedVenueCosts =
                        listOf(VenueCost(CostKind.COMMISSION, MoneyAmount(BigDecimal("0.7"), "usd"), 3L)),
                ),
            )
        assertThat(env.payload["typedVenueCosts"]).isEqualTo(
            listOf(mapOf("kind" to "COMMISSION", "amount" to BigDecimal("0.7"), "currency" to "USD", "ts" to 3L)),
        )
    }

    @Test
    fun `cancel suppress and latch signals become their own envelope types`() {
        fun env(signal: Signal) =
            InsightsTranslate.fromSignal(SignalEvent(signal, strategyId = "s", timestamp = 1_000L, sequenceId = 1L))!!

        val cancel = env(Signal.CancelPendingForSymbol("XAUUSD"))
        assertThat(cancel.type).isEqualTo("signal.cancel")
        assertThat(cancel.payload).containsEntry("intent", "CANCEL_PENDING_FOR_SYMBOL")

        val suppressed = env(Signal.Suppressed("XAUUSD", "cooldown"))
        assertThat(suppressed.type).isEqualTo("signal.suppressed")
        assertThat(suppressed.payload).containsEntry("reason", "cooldown")

        val latch =
            env(
                Signal.ArmLatch(
                    LatchManagerFixture.compiledLatch("2000", "5", 60_000L),
                    LatchManagerFixture.ec("XAUUSD"),
                ),
            )
        assertThat(latch.type).isEqualTo("signal.latch_armed")
        assertThat(latch.payload)
            .containsEntry("intent", "ARM_LATCH")
            .containsEntry("streamAlias", "s")
            .containsEntry("armWindowMs", 60_000L)
            .containsEntry("expiresAt", 61_000L)
    }
}
