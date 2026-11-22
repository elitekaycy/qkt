package com.qkt.dsl.sets

import com.qkt.cli.BacktestContext
import com.qkt.cli.DynamicSetRuns
import com.qkt.cli.SymbolSetResolution
import com.qkt.instrument.EventContracts
import com.qkt.instrument.universe.UniverseFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SlotUniverseTest {
    private val hour = 3_600_000L
    private val h0 = 1_794_002_400_000L

    /** Contract [code] of family [family], listed at [h0] and expiring [expiry] hours after it. */
    private fun contract(
        family: String,
        code: String,
        expiry: Int,
    ) = EventContracts.meta(
        "KALSHI:${family}_$code",
        EventContracts.terms().copy(
            root = "KALSHI:$family",
            event = "$family-$expiry",
            openAtMs = h0,
            closeAtMs = h0 + expiry * hour,
            expiryMs = h0 + expiry * hour,
        ),
    )

    private val rows =
        listOf(
            contract("KXA", "H3_T1", 3),
            contract("KXA", "H1_T1", 1),
            contract("KXB", "H2_T1", 2),
            contract("KXB", "H2_T2", 2),
            UniverseFixtures.eurusd,
        )

    private fun spec(text: String) = SymbolSetSpec.parse(text) as SymbolSetSpec.Filtered

    @Test
    fun `a slot universe names a slot selector after the star, moves, and round-trips its canonical text`() {
        val slots = spec("KALSHI:*@front.p70 WHERE family GLOB \"KX*\" TOP 2 BY expiry_at ASC")
        assertThat(slots.slot).isEqualTo("front.p70")
        assertThat(slots.dynamic).isFalse()
        assertThat(slots.moves).isTrue()
        assertThat(slots.canonical).startsWith("KALSHI:*@front.p70 WHERE family GLOB \"KX*\" TOP 2")
        assertThat(SymbolSetSpec.parse(slots.canonical)).isEqualTo(slots)
        assertThat(spec("KALSHI:* WHERE kind = event TOP 1 BY symbol").slot).isNull()
        assertThatThrownBy { SymbolSetSpec.parse("KALSHI:*@later.p70 WHERE kind = event TOP 1 BY symbol") }
            .hasMessageContaining("unknown slot event selector '@later'")
        assertThatThrownBy { SymbolSetSpec.parse("KALSHI:*@front.p99x WHERE kind = event TOP 1 BY symbol") }
            .hasMessageContaining("unknown slot strike selector 'p99x'")
        assertThatThrownBy { SymbolSetSpec.parse("KALSHI:*@front WHERE kind = event TOP 1 BY symbol") }
            .hasMessageContaining("expected '.<strike>' after '@front'")
    }

    @Test
    fun `families rank through their front contract and leave once nothing of theirs is listed`() {
        val set = DynamicSetMembership(spec("KALSHI:*@front.p70 WHERE kind = event TOP 2 BY expiry_at ASC"), rows, hour)
        assertThat(set.membersAt(h0)).containsExactly("KXA@front.p70", "KXB@front.p70")
        assertThat(set.membersAt(h0 + hour)).containsExactly("KXB@front.p70", "KXA@front.p70")
        assertThat(set.membersAt(h0 + 2 * hour)).containsExactly("KXA@front.p70")
        assertThat(set.membersAt(h0 + 3 * hour)).isEmpty()
        val one = DynamicSetMembership(spec("KALSHI:*@next.atm WHERE family = KXB TOP 5 BY symbol"), rows, hour)
        assertThat(one.membersAt(h0)).containsExactly("KXB@next.atm")
    }

    @Test
    fun `a backtest refuses a slot universe that reads market fields`() {
        val ast =
            DynamicSetFixtures.ast(
                DynamicSetFixtures.source.replace(
                    "BACKTEST:* WHERE kind = event TOP 2 BY symbol DESC DYNAMIC",
                    "KALSHI:*@front.p70 WHERE volume > 1 TOP 2 BY symbol",
                ),
            )
        assertThatThrownBy { DynamicSetRuns.plan(ast, SymbolSetResolution.FileCatalog(rows), h0, h0, false) {} }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessage("symbol set 's' is a slot universe, which cannot read market fields yet")
    }
}
