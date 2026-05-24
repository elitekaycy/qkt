package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackOrchestratorEntryQtyTest {
    @Test
    fun `proportional STACK_AT fires with qty equal to parentQty times scaling factor`() {
        val emitted = mutableListOf<Signal>()
        val orchestrator =
            StackOrchestrator(
                clock = FixedClock(0L),
                emit = { sig -> emitted.add(sig) },
            )
        val tiers =
            listOf(
                CompiledStackTier(
                    mfeThreshold = BigDecimal("1.0"),
                    withinMs = 60_000L,
                    resolveStackQuantity = { p -> p.multiply(BigDecimal("0.5")) },
                    slDistance = BigDecimal("2.0"),
                    tpDistance = BigDecimal("4.0"),
                ),
            )
        orchestrator.onPrimaryFilled(
            parentLegId = "parent-1",
            parentSymbol = "EXNESS:XAUUSD",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("4500"),
            parentQty = BigDecimal("0.40"),
            tiers = tiers,
        )

        orchestrator.onTick("EXNESS:XAUUSD", BigDecimal("4501.5"))

        val bracket =
            emitted
                .filterIsInstance<Signal.Submit>()
                .map { it.request }
                .filterIsInstance<OrderRequest.Bracket>()
                .single()
        assertThat(bracket.quantity).isEqualByComparingTo(BigDecimal("0.20"))
    }
}
