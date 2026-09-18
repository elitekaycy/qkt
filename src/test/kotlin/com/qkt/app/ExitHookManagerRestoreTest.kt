package com.qkt.app

import com.qkt.app.ExitHookManagerFixtures.StubDslStrategy
import com.qkt.app.ExitHookManagerFixtures.bracket
import com.qkt.app.ExitHookManagerFixtures.closeRequest
import com.qkt.app.ExitHookManagerFixtures.fill
import com.qkt.app.ExitHookManagerFixtures.ref
import com.qkt.common.Side
import com.qkt.execution.ExitReason
import com.qkt.persistence.NoopStatePersistor
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExitHookManagerRestoreTest {
    @Test
    fun `active binding restores and validates its compiled fingerprint`() {
        val persistor = NoopStatePersistor()
        val first = ExitHookManager(persistor)
        first.bind("s", StubDslStrategy(ref)) {}
        first.register("s", bracket(), ref)
        first.onFill(fill("entry", Side.BUY, "100", "2"), BigDecimal.ZERO, BigDecimal("2"), false)
        val saved = persistor.loadExitHooks("s")

        val restoredStrategy = StubDslStrategy(ref)
        val emitted = mutableListOf<Signal>()
        val restored = ExitHookManager(persistor)
        restored.bind("s", restoredStrategy) { emitted.add(it) }
        restored.onFill(fill("parent-tp", Side.SELL, "110", "2"), BigDecimal("20"), BigDecimal.ZERO, true)

        assertThat(restoredStrategy.exits.single().reason).isEqualTo(ExitReason.TAKE_PROFIT)
        assertThat(emitted).hasSize(1)

        persistor.saveExitHooks(
            "s",
            saved.map { it.copy(fingerprint = "stale") },
        )
        assertThatThrownBy {
            ExitHookManager(persistor).bind("s", StubDslStrategy(ref)) {}
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `manual close correlation survives restart`() {
        val persistor = NoopStatePersistor()
        val first = ExitHookManager(persistor)
        first.bind("s", StubDslStrategy(ref)) {}
        first.register("s", bracket(), ref)
        first.onFill(
            fill("entry", Side.BUY, "100", "2", brokerOrderId = "ticket-1"),
            BigDecimal.ZERO,
            BigDecimal("2"),
            false,
        )
        first.trackCloseRequest("s", closeRequest("manual-close", "ticket-1"))

        val restoredStrategy = StubDslStrategy(ref)
        val restored = ExitHookManager(persistor)
        restored.bind("s", restoredStrategy) {}
        restored.onFill(
            fill("manual-close", Side.SELL, "103", "2", brokerOrderId = "ticket-1"),
            BigDecimal("6"),
            BigDecimal.ZERO,
            true,
        )

        assertThat(restoredStrategy.exits.single().reason).isEqualTo(ExitReason.CLOSE)
        assertThat(persistor.loadExitHooks("s")).isEmpty()
    }
}
