package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.StatePersistor
import com.qkt.strategy.Signal
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StackExitAfterRestoreTest {
    private val tier =
        CompiledStackTier(
            mfeThreshold = BigDecimal("0.05"),
            withinMs = 90_000L,
            resolveStackQuantity = { BigDecimal("0.05") },
            slDistance = BigDecimal("15"),
            tpDistance = BigDecimal("0.10"),
            maeRecoverDistance = null,
        )

    private fun armBeforeRestart(
        persistor: StatePersistor,
        clock: FixedClock,
    ) {
        StackOrchestrator(clock = clock, strategyId = "burst", persistor = persistor, emit = {})
            .onPrimaryFilled(
                parentLegId = "seed",
                parentSymbol = "EXNESS:XAUUSD",
                parentSide = Side.BUY,
                parentEntryPrice = BigDecimal("4282.00"),
                parentQty = BigDecimal("0.01"),
                tiers = listOf(tier),
                exitAfterMs = 90_000L,
            )
    }

    @Test
    fun `a tier armed before a restart still carries the seed's EXIT AFTER when it fires after`(
        @TempDir dir: Path,
    ) {
        val clock = FixedClock(time = 1_000L)
        armBeforeRestart(FileStatePersistor(dir), clock)

        clock.time = 11_000L
        val restarted = FileStatePersistor(dir)
        val fired = mutableListOf<Signal>()
        val orch =
            StackOrchestrator(clock = clock, strategyId = "burst", persistor = restarted, emit = { fired.add(it) })
        orch.restoreEngine(
            parentLegId = "seed",
            parentSymbol = "EXNESS:XAUUSD",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("4282.00"),
            persisted = restarted.loadPendingStacks("burst").getValue("seed"),
        )
        orch.onTick("EXNESS:XAUUSD", BigDecimal("4282.10"))

        assertThat(fired).hasSize(1)
        val request = (fired.single() as Signal.Submit).request
        assertThat(request).isInstanceOf(OrderRequest.TimeExit::class.java)
        assertThat((request as OrderRequest.TimeExit).holdMs).isEqualTo(90_000L)
    }

    @Test
    fun `the hold is saved with the tier state`(
        @TempDir dir: Path,
    ) {
        armBeforeRestart(FileStatePersistor(dir), FixedClock(time = 1_000L))

        assertThat(FileStatePersistor(dir).loadPendingStacks("burst").getValue("seed").exitAfterMs)
            .isEqualTo(90_000L)
    }
}
