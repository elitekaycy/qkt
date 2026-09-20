package com.qkt.app

import com.qkt.app.ExitHookManagerFixtures.StubDslStrategy
import com.qkt.app.ExitHookManagerFixtures.bracket
import com.qkt.app.ExitHookManagerFixtures.closeRequest
import com.qkt.app.ExitHookManagerFixtures.fill
import com.qkt.app.ExitHookManagerFixtures.ref
import com.qkt.common.Side
import com.qkt.execution.ExitReason
import com.qkt.persistence.NoopStatePersistor
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExitHookManagerCloseAllocationTest {
    @Test
    fun `ticketed close targets only its binding when entries share a symbol`() {
        val strategy = StubDslStrategy(ref)
        val manager = ExitHookManager(NoopStatePersistor())
        manager.bind("s", strategy) {}
        manager.register("s", bracket(parentId = "a", entryId = "entry-a"), ref)
        manager.register("s", bracket(parentId = "b", entryId = "entry-b"), ref)
        manager.onFill(
            fill("entry-a", Side.BUY, "100", "1", brokerOrderId = "ticket-a"),
            BigDecimal.ZERO,
            BigDecimal.ONE,
            false,
        )
        manager.onFill(
            fill("entry-b", Side.BUY, "101", "1", brokerOrderId = "ticket-b"),
            BigDecimal.ZERO,
            BigDecimal("2"),
            false,
        )
        manager.trackCloseRequest("s", closeRequest("close-a", "ticket-a"))
        manager.onFill(
            fill("close-a", Side.SELL, "105", "1", brokerOrderId = "ticket-a"),
            BigDecimal("5"),
            BigDecimal.ONE,
            true,
        )

        val exit = strategy.exits.single()
        assertThat(exit.reason).isEqualTo(ExitReason.CLOSE)
        assertThat(exit.quantity).isEqualByComparingTo("1")
        assertThat(exit.pnl).isEqualByComparingTo("5")
    }

    @Test
    fun `netting-ticket close allocates quantity and pnl without duplication`() {
        val strategy = StubDslStrategy(ref)
        val manager = ExitHookManager(NoopStatePersistor())
        manager.bind("s", strategy) {}
        manager.register("s", bracket(parentId = "a", entryId = "entry-a"), ref)
        manager.register("s", bracket(parentId = "b", entryId = "entry-b"), ref)
        manager.onFill(
            fill("entry-a", Side.BUY, "100", "1", brokerOrderId = "shared"),
            BigDecimal.ZERO,
            BigDecimal.ONE,
            false,
        )
        manager.onFill(
            fill("entry-b", Side.BUY, "100", "1", brokerOrderId = "shared"),
            BigDecimal.ZERO,
            BigDecimal("2"),
            false,
        )
        manager.trackCloseRequest("s", closeRequest("flatten", "shared"))
        manager.onFill(
            fill("flatten", Side.SELL, "105", "2", brokerOrderId = "shared"),
            BigDecimal("10"),
            BigDecimal.ZERO,
            true,
        )

        assertThat(strategy.exits).hasSize(2)
        assertThat(strategy.exits.map { it.quantity }).allMatch { it.compareTo(BigDecimal.ONE) == 0 }
        assertThat(strategy.exits.map { it.pnl }).allMatch { it.compareTo(BigDecimal("5")) == 0 }
    }
}
