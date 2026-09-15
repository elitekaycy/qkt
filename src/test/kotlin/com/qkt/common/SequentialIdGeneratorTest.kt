package com.qkt.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SequentialIdGeneratorTest {
    @Test
    fun `resumes past the ids a restarted session restored`() {
        val ids = SequentialIdGenerator(prefix = "dsl-edge_fill_sell-")
        // A restored bracket (--1) with its entry (--0) and an unrelated strategy's id.
        ids.resumePast(listOf("dsl-edge_fill_sell--1", "dsl-edge_fill_sell--0", "dsl-other--9", "recovered-42"))
        assertThat(ids.next()).isEqualTo("dsl-edge_fill_sell--2")
    }

    @Test
    fun `resume never moves the counter backwards`() {
        val ids = SequentialIdGenerator(prefix = "dsl-x-")
        repeat(5) { ids.next() }
        ids.resumePast(listOf("dsl-x--1"))
        assertThat(ids.next()).isEqualTo("dsl-x--5")
        ids.resumePast(emptyList())
        assertThat(ids.next()).isEqualTo("dsl-x--6")
    }

    @Test
    fun `session order ids carry the strategy id so sessions under one magic never collide`() {
        val a = SequentialIdGenerator.forSession(listOf("btc_I_opposite"))
        val b = SequentialIdGenerator.forSession(listOf("btc_E_resize"))
        assertThat(a.next()).isEqualTo("ORD-btc_I_opposite-0")
        assertThat(b.next()).isEqualTo("ORD-btc_E_resize-0")
        // A restart resumes past the ids it restored, like the DSL generator does.
        a.resumePast(listOf("ORD-btc_I_opposite-4", "ORD-btc_E_resize-9"))
        assertThat(a.next()).isEqualTo("ORD-btc_I_opposite-5")
    }

    @Test
    fun `only this generator's ids count`() {
        val ids = SequentialIdGenerator(prefix = "dsl-x-")
        assertThat(ids.sequenceOf("dsl-x--12")).isEqualTo(12L)
        assertThat(ids.sequenceOf("dsl-xy--12")).isNull()
        assertThat(ids.sequenceOf("dsl-x--abc")).isNull()
        assertThat(ids.sequenceOf("ORD-3")).isNull()
    }
}
