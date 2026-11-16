package com.qkt.instrument.universe

import com.qkt.instrument.universe.CatalogField.CATEGORY
import com.qkt.instrument.universe.CatalogField.CONTRACT_SIZE
import com.qkt.instrument.universe.CatalogField.CURRENCY
import com.qkt.instrument.universe.CatalogField.KIND
import com.qkt.instrument.universe.UniverseFixtures.NOW_MS
import com.qkt.instrument.universe.UniverseFixtures.aapl
import com.qkt.instrument.universe.UniverseFixtures.btcPerp
import com.qkt.instrument.universe.UniverseFixtures.eurusd
import com.qkt.instrument.universe.UniverseFixtures.xauusd
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UniverseFilterTest {
    private fun num(v: String) = CatalogValue.Num(BigDecimal(v))

    private fun str(v: String) = CatalogValue.Str(v)

    private fun cmp(
        field: CatalogField,
        op: CompareOp,
        value: CatalogValue,
    ) = UniverseFilter.Compare(field, op, value)

    @Test
    fun `numbers compare numerically under every operator`() {
        val size100 = xauusd
        assertThat(cmp(CONTRACT_SIZE, CompareOp.EQ, num("100.0")).matches(size100, NOW_MS)).isTrue()
        assertThat(cmp(CONTRACT_SIZE, CompareOp.NE, num("100")).matches(size100, NOW_MS)).isFalse()
        assertThat(cmp(CONTRACT_SIZE, CompareOp.LT, num("100")).matches(size100, NOW_MS)).isFalse()
        assertThat(cmp(CONTRACT_SIZE, CompareOp.LE, num("100")).matches(size100, NOW_MS)).isTrue()
        assertThat(cmp(CONTRACT_SIZE, CompareOp.GT, num("99")).matches(size100, NOW_MS)).isTrue()
        assertThat(cmp(CONTRACT_SIZE, CompareOp.GE, num("101")).matches(size100, NOW_MS)).isFalse()
    }

    @Test
    fun `words compare ignoring case and a missing field compares false`() {
        assertThat(cmp(KIND, CompareOp.EQ, str("SPOT")).matches(eurusd, NOW_MS)).isTrue()
        assertThat(cmp(CATEGORY, CompareOp.LT, str("Metals")).matches(eurusd, NOW_MS)).isTrue()
        assertThat(cmp(CATEGORY, CompareOp.EQ, str("Metals")).matches(btcPerp, NOW_MS)).isFalse()
        assertThat(cmp(CATEGORY, CompareOp.NE, str("Metals")).matches(btcPerp, NOW_MS)).isFalse()
    }

    @Test
    fun `a number against a word is unequal and nothing else`() {
        assertThat(cmp(CONTRACT_SIZE, CompareOp.EQ, str("100")).matches(xauusd, NOW_MS)).isFalse()
        assertThat(cmp(CONTRACT_SIZE, CompareOp.NE, str("100")).matches(xauusd, NOW_MS)).isTrue()
        assertThat(cmp(CONTRACT_SIZE, CompareOp.GT, str("1")).matches(xauusd, NOW_MS)).isFalse()
        assertThat(cmp(CATEGORY, CompareOp.EQ, num("1")).matches(xauusd, NOW_MS)).isFalse()
    }

    @Test
    fun `IN, GLOB, AND, OR and NOT combine clauses`() {
        val oneOf = UniverseFilter.OneOf(CURRENCY, listOf(str("usd"), str("EUR")))
        assertThat(oneOf.matches(eurusd, NOW_MS)).isTrue()
        assertThat(oneOf.matches(btcPerp, NOW_MS)).isFalse()
        assertThat(oneOf.matches(UniverseFixtures.btcCall, NOW_MS)).isFalse()
        val glob = UniverseFilter.Glob(CATEGORY, "Stocks*")
        assertThat(glob.matches(aapl, NOW_MS)).isTrue()
        assertThat(glob.matches(eurusd, NOW_MS)).isFalse()
        assertThat(glob.matches(btcPerp, NOW_MS)).isFalse()
        assertThat(UniverseFilter.Glob(CONTRACT_SIZE, "100*").matches(eurusd, NOW_MS)).isTrue()
        val and = UniverseFilter.And(glob, cmp(KIND, CompareOp.EQ, str("equity")))
        assertThat(and.matches(aapl, NOW_MS)).isTrue()
        assertThat(and.matches(eurusd, NOW_MS)).isFalse()
        val or = UniverseFilter.Or(glob, cmp(KIND, CompareOp.EQ, str("spot")))
        assertThat(or.matches(eurusd, NOW_MS)).isTrue()
        assertThat(or.matches(btcPerp, NOW_MS)).isFalse()
        assertThat(UniverseFilter.Not(glob).matches(eurusd, NOW_MS)).isTrue()
        assertThat(UniverseFilter.Not(glob).matches(aapl, NOW_MS)).isFalse()
    }

    @Test
    fun `canonical text spells every clause one way`() {
        val filter =
            UniverseFilter.And(
                UniverseFilter.Or(
                    cmp(CATEGORY, CompareOp.EQ, str("a \"quoted\" \\ name")),
                    UniverseFilter.OneOf(KIND, listOf(str("spot"), num("1.50"))),
                ),
                UniverseFilter.Not(UniverseFilter.Glob(CATEGORY, "Stocks*")),
            )
        assertThat(filter.canonical)
            .isEqualTo(
                """((category = "a \"quoted\" \\ name" OR kind IN ("spot", 1.50)) AND NOT category GLOB "Stocks*")""",
            )
        assertThat(cmp(CONTRACT_SIZE, CompareOp.GE, num("-2")).canonical).isEqualTo("contract_size >= -2")
    }

    @Test
    fun `ordering puts numbers before words and compares each kind its own way`() {
        val ordering = CatalogValue.ordering
        assertThat(ordering.compare(num("2"), num("10"))).isNegative()
        assertThat(ordering.compare(str("b"), str("A"))).isPositive()
        assertThat(ordering.compare(num("1"), str("a"))).isNegative()
        assertThat(ordering.compare(str("a"), num("1"))).isPositive()
        assertThat(CatalogValue.same(num("1"), str("1"))).isFalse()
    }
}
