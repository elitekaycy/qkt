package com.qkt.dsl.sets

import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.EventContracts
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.CatalogValue
import com.qkt.instrument.universe.CompareOp
import com.qkt.instrument.universe.UniverseFilter
import com.qkt.instrument.universe.UniverseFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EventLadderTest {
    private val hour = 3_600_000L
    private val h0 = 1_794_002_400_000L

    /** Contract [code] of family [family]'s event closing [close] hours after [h0], listed from [h0]. */
    private fun contract(
        family: String,
        code: String,
        close: Int,
    ) = EventContracts.meta(
        "KALSHI:${family}_$code",
        EventContracts.terms().copy(
            root = "KALSHI:$family",
            event = "$family-$close",
            openAtMs = h0,
            closeAtMs = h0 + close * hour,
            expiryMs = h0 + close * hour,
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

    private fun members(
        text: String,
        atMs: Long,
    ) = DynamicSetMembership(spec(text), rows, hour).membersAt(atMs)

    @Test
    fun `a ladder names every contract of an event, for one family or all, and round-trips its canonical text`() {
        val one = spec("KALSHI:KXA@front.* WHERE kind = event TOP 10 BY symbol ASC")
        assertThat(one.family).isEqualTo("KXA")
        assertThat(one.slot).isEqualTo("front.*")
        assertThat(one.ladder).isTrue()
        assertThat(one.moves).isTrue()
        assertThat(one.canonical).startsWith("KALSHI:KXA@front.* WHERE")
        assertThat(SymbolSetSpec.parse(one.canonical)).isEqualTo(one)
        val each = spec("KALSHI:*@each.* WHERE kind = event TOP 10 BY symbol")
        assertThat(each.family).isNull()
        assertThat(SymbolSetSpec.parse(each.canonical)).isEqualTo(each)
        assertThat(spec("KALSHI:*@front.p70 WHERE kind = event TOP 1 BY symbol").ladder).isFalse()
        val ast =
            (
                Dsl.parse(
                    "STRATEGY l VERSION 1\nSYMBOLS\n" +
                        "    s = KALSHI:KXA@next.* WHERE kind = event TOP 5 BY symbol EVERY 1h\n" +
                        "RULES\n    FOR EACH m IN s DO\n      WHEN m.close > 1 THEN BUY m SIZING 1",
                ) as ParseResult.Success
            ).value
        assertThat(ast.streams.single { it.broker == SET_BROKER }.symbol).startsWith("KALSHI:KXA@next.* WHERE")
    }

    @Test
    fun `selectors that do not name a ladder or a slot are refused`() {
        fun refuse(text: String) = assertThatThrownBy { SymbolSetSpec.parse(text) }
        refuse("KALSHI:*@each.p70 WHERE kind = event TOP 1 BY symbol")
            .hasMessageContaining("@each takes every contract of each open event: '*@each.*'")
        refuse("KALSHI:*@later.* WHERE kind = event TOP 1 BY symbol")
            .hasMessageContaining("unknown event selector '@later'")
        refuse("KALSHI:KXA WHERE kind = event TOP 1 BY symbol").hasMessageContaining("expected '{' or '*' after")
        val kind = UniverseFilter.Compare(CatalogField.KIND, CompareOp.EQ, CatalogValue.Str("event"))
        assertThatThrownBy {
            SymbolSetSpec.Filtered("KALSHI", kind, 1, CatalogField.SYMBOL, false, false, "front.p70", "KXA")
        }.hasMessage("a family's set takes every contract of an event: KXA@front.p70")
    }

    @Test
    fun `front, next and each pick each family's open events, earliest close first`() {
        val front = "KALSHI:*@front.* WHERE kind = event TOP 10 BY symbol ASC"
        assertThat(members(front, h0)).containsExactly("KXA_H1_T1", "KXB_H2_T1", "KXB_H2_T2")
        assertThat(members(front, h0 + hour)).containsExactly("KXA_H3_T1", "KXB_H2_T1", "KXB_H2_T2")
        assertThat(members("KALSHI:KXA@each.* WHERE kind = event TOP 10 BY symbol ASC", h0))
            .containsExactly("KXA_H1_T1", "KXA_H3_T1")
        val next = "KALSHI:KXA@next.* WHERE kind = event TOP 10 BY symbol ASC"
        assertThat(members(next, h0)).containsExactly("KXA_H3_T1")
        assertThat(members(next, h0 + hour)).isEmpty()
        assertThat(members("KALSHI:*@front.* WHERE kind = event TOP 2 BY symbol ASC", h0 + 3 * hour)).isEmpty()
    }
}
