package com.qkt.dsl.compile

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarmupGateTest {
    @Test
    fun `empty perStream map means everything is warm`() {
        val gate = WarmupGate(emptyMap())
        assertThat(gate.isWarm("anything")).isTrue
        assertThat(gate.isWarm(setOf("a", "b"))).isTrue
    }

    @Test
    fun `unconfigured alias is treated as warm`() {
        val gate = WarmupGate(mapOf("a" to 3))
        assertThat(gate.isWarm("b")).isTrue
    }

    @Test
    fun `configured alias starts cold and warms at the Nth closed candle`() {
        val gate = WarmupGate(mapOf("a" to 3))
        assertThat(gate.isWarm("a")).isFalse
        gate.onClosedCandle("a")
        assertThat(gate.isWarm("a")).isFalse
        gate.onClosedCandle("a")
        assertThat(gate.isWarm("a")).isFalse
        gate.onClosedCandle("a")
        assertThat(gate.isWarm("a")).isTrue
        gate.onClosedCandle("a")
        assertThat(gate.isWarm("a")).isTrue
    }

    @Test
    fun `set form requires every alias warm`() {
        val gate = WarmupGate(mapOf("a" to 2, "b" to 1))
        gate.onClosedCandle("a")
        gate.onClosedCandle("a")
        assertThat(gate.isWarm(setOf("a", "b"))).isFalse
        gate.onClosedCandle("b")
        assertThat(gate.isWarm(setOf("a", "b"))).isTrue
    }

    @Test
    fun `recordBars pre-credits the alias counter`() {
        val gate = WarmupGate(mapOf("a" to 5))
        gate.recordBars("a", 5)
        assertThat(gate.isWarm("a")).isTrue
    }

    @Test
    fun `recordBars is additive with onClosedCandle`() {
        val gate = WarmupGate(mapOf("a" to 10))
        gate.recordBars("a", 7)
        assertThat(gate.isWarm("a")).isFalse
        gate.onClosedCandle("a")
        gate.onClosedCandle("a")
        gate.onClosedCandle("a")
        assertThat(gate.isWarm("a")).isTrue
    }

    @Test
    fun `recordBars with non-positive count is a no-op`() {
        val gate = WarmupGate(mapOf("a" to 5))
        gate.recordBars("a", 0)
        gate.recordBars("a", -3)
        assertThat(gate.isWarm("a")).isFalse
    }

    @Test
    fun `empty set is always warm`() {
        val gate = WarmupGate(mapOf("a" to 100))
        assertThat(gate.isWarm(emptySet())).isTrue
    }
}
