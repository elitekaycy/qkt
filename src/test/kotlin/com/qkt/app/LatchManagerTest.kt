package com.qkt.app

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatchManagerTest {
    @Test
    fun `up-break fans out the entries as BUY orders`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(
            LatchManagerFixture.compiledLatch(ref = "2000.0", offset = "0.50", windowMs = 300_000L),
            ec = ec,
            now = 1_000L,
        )
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.6", 2_000L))
        assertThat(emitted).hasSize(1)
        assertThat(emitted.single().side).isEqualTo(Side.BUY)
    }

    @Test
    fun `down-break fans out as SELL`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(LatchManagerFixture.compiledLatch("2000.0", "0.50", 300_000L), ec = ec, now = 1_000L)
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "1999.4", 2_000L))
        assertThat(emitted.single().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `no cross within the arm window emits nothing and drops the latch`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(LatchManagerFixture.compiledLatch("2000.0", "0.50", 300_000L), ec = ec, now = 1_000L)
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.0", 1_000L + 300_001L))
        assertThat(emitted).isEmpty()
        // a later in-range cross does nothing — the latch is gone
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.6", 1_000L + 300_002L))
        assertThat(emitted).isEmpty()
    }
}
