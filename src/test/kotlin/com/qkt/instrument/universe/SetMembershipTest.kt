package com.qkt.instrument.universe

import com.qkt.instrument.universe.MembershipChange.JOIN
import com.qkt.instrument.universe.MembershipChange.LEAVE
import com.qkt.instrument.universe.MembershipChange.RELEASE
import com.qkt.instrument.universe.MembershipChange.RESTORE
import com.qkt.instrument.universe.MembershipChange.STAY
import com.qkt.instrument.universe.MembershipChange.STICK
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SetMembershipTest {
    @Test
    fun `the first evaluation joins every ranked member in rank order`() {
        val step = SetMembership().step(listOf("B", "A", "C"), held = emptySet())
        assertThat(step.active).containsExactly("B", "A", "C")
        assertThat(step.changes).containsExactly(
            MemberChange("B", JOIN, 1),
            MemberChange("A", JOIN, 2),
            MemberChange("C", JOIN, 3),
        )
    }

    @Test
    fun `a flat member that falls out of the ranking leaves, leaves before joins`() {
        val set = SetMembership().apply { step(listOf("A", "B"), emptySet()) }
        val step = set.step(listOf("C", "A"), held = emptySet())
        assertThat(step.active).containsExactly("C", "A")
        assertThat(step.changes).containsExactly(MemberChange("B", LEAVE, null), MemberChange("C", JOIN, 1))
    }

    @Test
    fun `a member that holds stays active after it leaves the ranking, and is released once flat`() {
        val set = SetMembership().apply { step(listOf("A", "B"), emptySet()) }
        val stuck = set.step(listOf("A", "C"), held = setOf("B"))
        assertThat(stuck.active).containsExactly("A", "C", "B")
        assertThat(stuck.changes).containsExactly(MemberChange("B", STICK, null), MemberChange("C", JOIN, 2))
        val still = set.step(listOf("A", "C"), held = setOf("B"))
        assertThat(still.changes).isEmpty()
        assertThat(still.active).containsExactly("A", "C", "B")
        val released = set.step(listOf("A", "C"), held = emptySet())
        assertThat(released.changes).containsExactly(MemberChange("B", RELEASE, null))
        assertThat(released.active).containsExactly("A", "C")
        assertThat(set.active).containsExactly("A", "C")
    }

    @Test
    fun `a sticky member back in the ranking stays, with no new join`() {
        val set = SetMembership().apply { step(listOf("A", "B"), emptySet()) }
        set.step(listOf("A"), held = setOf("B"))
        val back = set.step(listOf("B", "A", "B"), held = emptySet())
        assertThat(back.changes).containsExactly(MemberChange("B", STAY, 1))
        assertThat(back.active).containsExactly("B", "A")
        assertThat(set.step(listOf("A"), emptySet()).changes).containsExactly(MemberChange("B", LEAVE, null))
    }

    @Test
    fun `equal inputs give equal steps, changes ordered by symbol then rank`() {
        fun run(): List<MembershipStep> {
            val set = SetMembership()
            return listOf(
                set.step(listOf("D", "C", "B", "A"), emptySet()),
                set.step(listOf("E", "F"), setOf("C")),
            )
        }
        val steps = run()
        assertThat(steps).isEqualTo(run())
        assertThat(steps[1].changes.map { it.member to it.change }).containsExactly(
            "A" to LEAVE,
            "B" to LEAVE,
            "C" to STICK,
            "D" to LEAVE,
            "E" to JOIN,
            "F" to JOIN,
        )
    }

    @Test
    fun `a restart rebuilds membership from the ranking and the held members, reported as restored`() {
        val set = SetMembership()
        val step = set.restore(listOf("A", "B"), held = setOf("Z", "B"))
        assertThat(step.active).containsExactly("A", "B", "Z")
        assertThat(step.changes).containsExactly(
            MemberChange("A", RESTORE, 1),
            MemberChange("B", RESTORE, 2),
            MemberChange("Z", RESTORE, null),
        )
        assertThat(set.step(listOf("A", "B"), emptySet()).changes).containsExactly(MemberChange("Z", RELEASE, null))
    }
}
