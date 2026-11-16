package com.qkt.events

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.instrument.universe.MembershipChange
import com.qkt.instrument.universe.SetMembership
import com.qkt.observe.encodeAuditLine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SetMembershipChangedTest {
    private val step = SetMembership().step(listOf("KALSHI:B", "KALSHI:A"), emptySet())

    @Test
    fun `a step becomes one journal event per change, in the step's order`() {
        val events = SetMembershipChanged.of("carry", "hourly", 3_600_000L, step)
        assertThat(events).containsExactly(
            SetMembershipChanged("carry", "hourly", "KALSHI:B", MembershipChange.JOIN, 1, 3_600_000L),
            SetMembershipChanged("carry", "hourly", "KALSHI:A", MembershipChange.JOIN, 2, 3_600_000L),
        )
    }

    @Test
    fun `the bus stamps it and the audit journal records it as a decision line`() {
        val bus = EventBus(FixedClock(7_000L), MonotonicSequenceGenerator())
        val received = mutableListOf<SetMembershipChanged>()
        bus.subscribe<SetMembershipChanged> { received.add(it) }
        bus.publish(SetMembershipChanged("carry", "hourly", "KALSHI:A", MembershipChange.STICK, null, 3_600_000L))
        val stamped = received.single()
        assertThat(stamped.timestamp).isEqualTo(7_000L)
        assertThat(stamped.sequenceId).isNotNegative()
        val line = encodeAuditLine(stamped)
        assertThat(line)
            .contains("\"strategyId\":\"carry\"")
            .contains("\"symbol\":\"KALSHI:A\"")
            .contains("\"set\":\"hourly\",\"change\":\"stick\",\"rank\":null,\"windowEndMs\":3600000")
        assertThat(encodeAuditLine(SetMembershipChanged.of("carry", "hourly", 60_000L, step).first()))
            .contains("\"change\":\"join\",\"rank\":1,")
    }
}
