package com.qkt.dsl.sets

import com.qkt.instrument.EventContracts
import com.qkt.instrument.universe.MemberChange
import com.qkt.instrument.universe.MembershipChange
import com.qkt.instrument.universe.UniverseFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DynamicSetMembershipTest {
    private val hour = 3_600_000L
    private val h0 = 1_794_002_400_000L

    /** An event contract listed from hour [open] until hour [expiry] after [h0]. */
    private fun contract(
        code: String,
        open: Int,
        expiry: Int,
    ) = EventContracts.meta(
        "KALSHI:$code",
        EventContracts.terms().copy(
            openAtMs = h0 + open * hour,
            closeAtMs = h0 + expiry * hour,
            expiryMs = h0 + expiry * hour,
        ),
    )

    private val rows =
        listOf(contract("A", 0, 3), contract("B", 1, 5), contract("C", 2, 6), UniverseFixtures.eurusd)

    private fun spec(text: String) = SymbolSetSpec.parse(text) as SymbolSetSpec.Filtered

    private val dynamic = spec("KALSHI:* WHERE kind = event TOP 2 BY symbol ASC DYNAMIC")

    @Test
    fun `DYNAMIC is a contextual word that round-trips through the canonical spec`() {
        assertThat(dynamic.dynamic).isTrue()
        assertThat(dynamic.canonical).isEqualTo("KALSHI:* WHERE kind = \"event\" TOP 2 BY symbol ASC DYNAMIC")
        assertThat(SymbolSetSpec.parse(dynamic.canonical)).isEqualTo(dynamic)
        assertThat(spec("KALSHI:* WHERE kind = event TOP 2 BY symbol").dynamic).isFalse()
    }

    @Test
    fun `members at a close are the rows listed and unexpired then, ranked and capped`() {
        val set = DynamicSetMembership(dynamic, rows, hour)
        assertThat(set.membersAt(h0)).containsExactly("A")
        assertThat(set.membersAt(h0 + hour)).containsExactly("A", "B")
        assertThat(set.membersAt(h0 + 2 * hour)).containsExactly("A", "B")
        assertThat(set.membersAt(h0 + 3 * hour)).containsExactly("B", "C")
        assertThat(set.membersAt(h0 + 6 * hour)).isEmpty()
    }

    @Test
    fun `it steps once per window close, as of that close, keeping a held member that left`() {
        val set = DynamicSetMembership(dynamic, rows, hour)
        assertThat(set.activeSymbols).isEmpty()
        val first = set.at(h0 + 300_000L) { emptySet() }
        assertThat(first?.closeMs).isEqualTo(h0)
        assertThat(first?.step?.changes).containsExactly(MemberChange("A", MembershipChange.JOIN, 1))
        assertThat(set.at(h0 + 600_000L) { emptySet() }).isNull()
        assertThat(set.at(h0 + hour)?.step?.changes).containsExactly(MemberChange("B", MembershipChange.JOIN, 2))
        assertThat(set.at(h0 + 2 * hour + 1)?.step?.changes).isEmpty()
        val third = set.at(h0 + 3 * hour + 5) { setOf("A") }
        assertThat(third?.closeMs).isEqualTo(h0 + 3 * hour)
        assertThat(third?.step?.changes).containsExactly(
            MemberChange("A", MembershipChange.STICK, null),
            MemberChange("C", MembershipChange.JOIN, 2),
        )
        assertThat(third?.step?.active).containsExactly("B", "C", "A")
        assertThat(set.activeSymbols).containsExactly("KALSHI:B", "KALSHI:C", "KALSHI:A")
    }

    private fun DynamicSetMembership.at(nowMs: Long) = at(nowMs) { emptySet() }

    @Test
    fun `a row is alive once an event opened and until any contract expires`() {
        val a = contract("A", 1, 2)
        assertThat(DynamicSetMembership.alive(a, h0)).isFalse()
        assertThat(DynamicSetMembership.alive(a, h0 + hour)).isTrue()
        assertThat(DynamicSetMembership.alive(a, h0 + 2 * hour)).isFalse()
        assertThat(DynamicSetMembership.alive(UniverseFixtures.btcPerp, h0)).isTrue()
        assertThat(DynamicSetMembership.alive(UniverseFixtures.esDec, UniverseFixtures.NOW_MS)).isTrue()
        assertThat(DynamicSetMembership.alive(UniverseFixtures.esDec, UniverseFixtures.NOW_MS + 31 * 86_400_000L))
            .isFalse()
    }

    @Test
    fun `only a DYNAMIC set over a positive window has a runtime membership`() {
        assertThatThrownBy { DynamicSetMembership(spec("KALSHI:* WHERE kind = event TOP 2 BY symbol"), rows, hour) }
            .hasMessageEndingWith("is resolved once, at start; it has no runtime membership")
        assertThatThrownBy { DynamicSetMembership(dynamic, rows, 0L) }
            .hasMessage("a set's window must be positive: 0 ms")
    }
}
