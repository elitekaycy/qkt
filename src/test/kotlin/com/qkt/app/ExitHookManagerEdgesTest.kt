package com.qkt.app

import com.qkt.app.ExitHookManagerFixtures.StubDslStrategy
import com.qkt.app.ExitHookManagerFixtures.bracket
import com.qkt.app.ExitHookManagerFixtures.closeRequest
import com.qkt.app.ExitHookManagerFixtures.fill
import com.qkt.app.ExitHookManagerFixtures.ref
import com.qkt.common.Side
import com.qkt.dsl.compile.ExitHookRef
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedExitHookBinding
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExitHookManagerEdgesTest {
    private val persistor = NoopStatePersistor()
    private val strategy = StubDslStrategy(ref)
    private val manager = ExitHookManager(persistor).apply { bind("s", strategy) {} }

    private fun openEntry(quantity: String = "2") {
        manager.register("s", bracket(), ref)
        manager.onFill(fill("entry", Side.BUY, "100", quantity), BigDecimal.ZERO, BigDecimal(quantity), false)
    }

    @Test
    fun `a hook whose fingerprint changed since compile is refused at registration`() {
        assertThatThrownBy { manager.register("s", bracket(), ExitHookRef(ref.definitionId, "stale")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("fingerprint changed")
    }

    @Test
    fun `composite parents other than brackets and stacks are refused`() {
        val child = closeRequest("child", "t")
        val oto =
            OrderRequest.OTO("oto", "XAUUSD", Side.BUY, BigDecimal.ONE, child, listOf(child), TimeInForce.GTC, 1L, "s")

        assertThatThrownBy { manager.register("s", oto, ref) }.hasMessageContaining("do not support OTO parents")
    }

    @Test
    fun `a manual close aimed at the entry leg fires the hook as a close`() {
        openEntry()
        val close = closeRequest("manual", "unrelated-ticket").copy(closesLegId = "entry", quantity = BigDecimal("2"))

        manager.trackCloseRequest("s", close)
        val closeFill = fill("manual", Side.SELL, "104", "2", brokerOrderId = "")
        manager.onFill(closeFill, BigDecimal("8"), BigDecimal.ZERO, true)

        val exit = strategy.exits.single()
        assertThat(exit.reason).isEqualTo(ExitReason.CLOSE)
        assertThat(exit.pnl).isEqualByComparingTo("8")
    }

    @Test
    fun `an unindexed reducing fill falls back to the symbol and keeps the venue's exit reason`() {
        openEntry()

        val liquidation =
            fill("venue-liq", Side.SELL, "90", "2", brokerOrderId = "").copy(exitReason = ExitReason.LIQUIDATION)
        manager.onFill(liquidation, BigDecimal("-20"), BigDecimal.ZERO, true)

        assertThat(strategy.exits.single().reason).isEqualTo(ExitReason.LIQUIDATION)
    }

    @Test
    fun `an unindexed reducing fill without a reason closes as a plain close`() {
        openEntry()

        val venueClose = fill("venue-close", Side.SELL, "101", "2", brokerOrderId = "")
        manager.onFill(venueClose, BigDecimal("2"), BigDecimal.ZERO, true)

        assertThat(strategy.exits.single().reason).isEqualTo(ExitReason.CLOSE)
    }

    @Test
    fun `an unindexed fill that did not reduce exposure touches no hook`() {
        openEntry()

        val other = fill("other", Side.SELL, "101", "2", brokerOrderId = "")
        manager.onFill(other, BigDecimal.ZERO, BigDecimal.ZERO, false)

        assertThat(strategy.exits).isEmpty()
        assertThat(persistor.loadExitHooks("s").single().activeQuantity).isEqualByComparingTo("2")
    }

    @Test
    fun `a partial exit is persisted and the hook fires once the rest closes`() {
        openEntry()

        manager.onFill(fill("parent-sl", Side.SELL, "95", "1"), BigDecimal("-5"), BigDecimal.ONE, true)

        assertThat(strategy.exits).isEmpty()
        val saved = persistor.loadExitHooks("s").single()
        assertThat(saved.activeQuantity).isEqualByComparingTo("1")
        assertThat(saved.exitQuantity).isEqualByComparingTo("1")

        manager.onFill(fill("parent-sl", Side.SELL, "94", "1"), BigDecimal("-6"), BigDecimal.ZERO, true)

        val exit = strategy.exits.single()
        assertThat(exit.reason).isEqualTo(ExitReason.STOP)
        assertThat(exit.quantity).isEqualByComparingTo("2")
        assertThat(exit.pnl).isEqualByComparingTo("-11")
    }

    @Test
    fun `a rejected entry drops its unfilled binding`() {
        manager.register("s", bracket(), ref)
        manager.onRejected(BrokerEvent.OrderRejected("someone-else", null, "x", strategyId = "s"))
        manager.onRejected(BrokerEvent.OrderRejected("entry", null, "no margin", strategyId = "s"))
        manager.onRejected(BrokerEvent.OrderRejected("entry", null, "again", strategyId = "s"))

        assertThat(persistor.loadExitHooks("s")).isEmpty()
        manager.onFill(fill("parent-sl", Side.SELL, "95", "2"), BigDecimal("-10"), BigDecimal.ZERO, true)
        assertThat(strategy.exits).isEmpty()
    }

    @Test
    fun `a cancelled close order no longer routes its fill to the hook`() {
        openEntry()
        manager.trackCloseRequest("s", closeRequest("close-1", "unknown-ticket"))
        manager.trackCloseRequest("s", closeRequest("close-2", "unknown-ticket").copy(closesLegId = "entry"))
        assertThat(persistor.loadExitHooks("s").single().closeOrderIds).containsExactly("close-2")

        manager.onCancelled(BrokerEvent.OrderCancelled("close-2", null, "user", strategyId = "s"))

        val saved = persistor.loadExitHooks("s").single()
        assertThat(saved.closeOrderIds).isEmpty()
        assertThat(saved.activeQuantity).isEqualByComparingTo("2")
        val lateFill = fill("close-2", Side.SELL, "101", "1", brokerOrderId = "")
        manager.onFill(lateFill, BigDecimal.ONE, BigDecimal.ONE, false)
        assertThat(strategy.exits).isEmpty()
    }

    @Test
    fun `a binding persisted under another strategy aborts the bind`() {
        persistor.saveExitHooks(
            "t",
            listOf(
                PersistedExitHookBinding(
                    bindingId = "parent:exit-hook-1",
                    strategyId = "intruder",
                    symbol = "XAUUSD",
                    entrySide = Side.BUY,
                    definitionId = ref.definitionId,
                    fingerprint = ref.fingerprint,
                    entryOrderIds = listOf("entry"),
                    stopOrderIds = emptyList(),
                    takeProfitOrderIds = emptyList(),
                    activeQuantity = BigDecimal.ONE,
                    exitQuantity = BigDecimal.ZERO,
                    exitPnl = BigDecimal.ZERO,
                ),
            ),
        )

        assertThatThrownBy { manager.bind("t", strategy) {} }.hasMessageContaining("belongs to intruder, not t")
    }
}
