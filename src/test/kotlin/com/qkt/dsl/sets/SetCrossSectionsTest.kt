package com.qkt.dsl.sets

import com.qkt.dsl.compile.Value
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SetCrossSectionsTest {
    private val members = mutableListOf("s.A", "s.B")
    private val sections = SetCrossSections { members }

    private fun n(v: Int) = Value.Num(BigDecimal(v))

    @Test
    fun `a close reads the current members' values once, a member with none reading Undefined`() {
        sections.post(0, "s.A", n(1))
        sections.post(0, "s.X", n(9))
        val first = sections.read(0, 60_000L)
        assertThat(first.values).containsExactly(n(1), Value.Undefined)
        assertThat(first.posted).containsEntry("s.X", n(9))

        sections.post(0, "s.B", n(2))
        assertThat(sections.read(0, 60_000L)).isSameAs(first)
        assertThat(first.values).containsExactly(n(1), Value.Undefined)
        members += "s.X"
        assertThat(sections.read(0, 120_000L).values).containsExactly(n(1), n(2), n(9))
        assertThat(sections.read(1, 120_000L).values).containsOnly(Value.Undefined)
    }

    @Test
    fun `a member that leaves is forgotten at every site`() {
        sections.post(0, "s.A", n(1))
        sections.post(1, "s.A", n(5))
        sections.post(1, "s.B", n(6))
        sections.forget("s.A")
        assertThat(sections.read(0, 60_000L).posted).isEmpty()
        assertThat(sections.read(1, 60_000L).values).containsExactly(Value.Undefined, n(6))
    }
}
