package com.qkt.observe.insights

import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.universe.MembershipChange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SetInsightsTest {
    private fun stamped(
        change: MembershipChange,
        rank: Int?,
    ) = SetMembershipChanged("rotator", "movers", "BINANCE:BTCUSDT", change, rank, 3_600_000L, 3_600_250L, 41L)

    @Test
    fun `a ranked join becomes a set membership envelope with every field the collector stores`() {
        val e = InsightsTranslate.fromSetMembershipChanged(stamped(MembershipChange.JOIN, 2))

        assertThat(e.type).isEqualTo("set.membership")
        assertThat(e.id).isEqualTo("event-set.membership-rotator-3600250-41")
        assertThat(e.seq).isEqualTo(41L)
        assertThat(e.ts).isEqualTo(3_600_250L)
        assertThat(e.strategyId).isEqualTo("rotator")
        assertThat(e.payload).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "strategyId" to "rotator",
                "set" to "movers",
                "member" to "BINANCE:BTCUSDT",
                "change" to "join",
                "rank" to 2,
                "closeTs" to 3_600_000L,
            ),
        )
    }

    @Test
    fun `an unranked change leaves the rank out, and no change states a reason`() {
        val e = InsightsTranslate.fromSetMembershipChanged(stamped(MembershipChange.UNAVAILABLE, null))

        assertThat(e.payload).doesNotContainKeys("rank", "reason")
        assertThat(e.payload["change"]).isEqualTo("unavailable")
    }

    @Test
    fun `every change kind is sent under its lower case name`() {
        val sent = MembershipChange.entries.map { InsightsTranslate.fromSetMembershipChanged(stamped(it, null)) }

        assertThat(sent.map { it.payload["change"] })
            .containsExactly("join", "leave", "stick", "release", "stay", "restore", "unavailable")
    }
}
