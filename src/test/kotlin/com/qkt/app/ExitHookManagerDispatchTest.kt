package com.qkt.app

import com.qkt.app.ExitHookManagerFixtures.StubDslStrategy
import com.qkt.app.ExitHookManagerFixtures.bracket
import com.qkt.app.ExitHookManagerFixtures.fill
import com.qkt.app.ExitHookManagerFixtures.ref
import com.qkt.common.Side
import com.qkt.dsl.compile.ExitContext
import com.qkt.execution.ExitReason
import com.qkt.persistence.NoopStatePersistor
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExitHookManagerDispatchTest {
    @Test
    fun `stop fill dispatches once with accumulated exit context`() {
        val persistor = NoopStatePersistor()
        val strategy = StubDslStrategy(ref)
        val emitted = mutableListOf<Signal>()
        val manager = ExitHookManager(persistor)
        manager.bind("s", strategy) { emitted.add(it) }
        manager.register("s", bracket(), ref)
        manager.onFill(fill("entry", Side.BUY, "100", "2"), BigDecimal.ZERO, BigDecimal("2"), false)
        manager.onFill(fill("parent-sl", Side.SELL, "95", "2"), BigDecimal("-10"), BigDecimal.ZERO, true)
        manager.onFill(fill("parent-sl", Side.SELL, "95", "2"), BigDecimal("-10"), BigDecimal.ZERO, false)

        assertThat(emitted).containsExactly(Signal.Sell("XAUUSD", BigDecimal.ONE))
        assertThat(strategy.exits).containsExactly(
            ExitContext(
                price = BigDecimal("95"),
                side = Side.SELL,
                quantity = BigDecimal("2"),
                pnl = BigDecimal("-10"),
                reason = ExitReason.STOP,
            ),
        )
        assertThat(persistor.loadExitHooks("s")).isEmpty()
    }

    @Test
    fun `deferred dispatch waits for the later lifecycle subscriber`() {
        val strategy = StubDslStrategy(ref)
        val emitted = mutableListOf<Signal>()
        val manager = ExitHookManager(NoopStatePersistor())
        manager.bind("s", strategy) { emitted.add(it) }
        manager.register("s", bracket(), ref)
        manager.onFill(fill("entry", Side.BUY, "100", "2"), BigDecimal.ZERO, BigDecimal("2"), false)
        val exit = fill("parent-tp", Side.SELL, "110", "2").copy(sequenceId = 42L)

        manager.onFill(
            exit,
            BigDecimal("20"),
            BigDecimal.ZERO,
            reducedExposure = true,
            deferDispatch = true,
        )

        assertThat(emitted).isEmpty()
        manager.dispatchReady(exit)
        assertThat(emitted).hasSize(1)
    }
}
