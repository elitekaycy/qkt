package com.qkt.observability

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class LatencyRegistryRejectedTest {
    @Test
    fun `a negative observation is logged and dropped instead of thrown`() {
        val r = LatencyRegistry(enabled = true, strategyIds = listOf("alpha"))

        assertThatCode {
            r.observe("alpha", LatencyStage.TICK_PROCESSING, -1L)
            r.observeAll(LatencyStage.TICK_PROCESSING, -5L)
        }.doesNotThrowAnyException()

        assertThat(
            r
                .snapshot()
                .strategies
                .getValue("alpha")
                .getValue(LatencyStage.TICK_PROCESSING)
                .count,
        ).isZero
    }
}
