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
    fun `a read gives the current members' latest values, a member with none reading Undefined`() {
        sections.post(0, "s.A", n(1))
        sections.post(0, "s.X", n(9))
        val first = sections.read(0)
        assertThat(first.values).containsExactly(n(1), Value.Undefined)
        assertThat(first.posted).containsEntry("s.X", n(9))

        sections.post(0, "s.B", n(2))
        sections.post(0, "s.A", n(3))
        assertThat(sections.read(0).values).containsExactly(n(3), n(2))
        members += "s.X"
        assertThat(sections.read(0).values).containsExactly(n(3), n(2), n(9))
        assertThat(sections.read(1).values).containsOnly(Value.Undefined)
    }

    @Test
    fun `a member that leaves is forgotten at every site`() {
        sections.post(0, "s.A", n(1))
        sections.post(1, "s.A", n(5))
        sections.post(1, "s.B", n(6))
        sections.forget("s.A")
        assertThat(sections.read(0).posted).isEmpty()
        assertThat(sections.read(1).values).containsExactly(Value.Undefined, n(6))
    }

    @Test
    fun `a strike neighbour reads the named member's value, Undefined without one or before it posts`() {
        val ladder =
            SetCrossSections({ member, step, _ -> "s.B".takeIf { member == "s.A" && step == 1 } }) { members }
        assertThat(ladder.neighbour(0, "s.A", 1, 0L)).isEqualTo(Value.Undefined)
        ladder.post(0, "s.A", n(1))
        assertThat(ladder.neighbour(0, "s.A", 1, 0L)).isEqualTo(Value.Undefined)
        ladder.post(0, "s.B", n(2))
        assertThat(ladder.neighbour(0, "s.A", 1, 0L)).isEqualTo(n(2))
        assertThat(ladder.neighbour(0, "s.A", -1, 0L)).isEqualTo(Value.Undefined)
        assertThat(sections.neighbour(0, "s.A", 1, 0L)).isEqualTo(Value.Undefined)
    }
}
