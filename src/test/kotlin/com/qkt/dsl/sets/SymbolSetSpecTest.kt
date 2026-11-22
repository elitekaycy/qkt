package com.qkt.dsl.sets

import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StreamDecl
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.CatalogValue
import com.qkt.instrument.universe.CompareOp
import com.qkt.instrument.universe.UniverseFilter
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SymbolSetSpecTest {
    private fun filtered(text: String) = SymbolSetSpec.parse(text) as SymbolSetSpec.Filtered

    @Test
    fun `a picked list keeps its venue and symbols in order`() {
        val spec = SymbolSetSpec.parse("EXNESS:{EURUSD, XAUUSD,KXHIGHNY_26NOV06_B73.5}")
        assertThat(spec).isEqualTo(SymbolSetSpec.Picked("EXNESS", listOf("EURUSD", "XAUUSD", "KXHIGHNY_26NOV06_B73.5")))
        assertThat(spec.canonical).isEqualTo("EXNESS:{EURUSD, XAUUSD, KXHIGHNY_26NOV06_B73.5}")
        assertThat(SymbolSetSpec.parse(spec.canonical)).isEqualTo(spec)
    }

    @Test
    fun `a filtered set parses its filter, count, ranking field and direction`() {
        val spec = filtered("ALPACA:* WHERE category = \"us_equity\" AND contract_size > 5 TOP 50 BY symbol")
        assertThat(spec.venue).isEqualTo("ALPACA")
        assertThat(spec.filter).isEqualTo(
            UniverseFilter.And(
                UniverseFilter.Compare(CatalogField.CATEGORY, CompareOp.EQ, CatalogValue.Str("us_equity")),
                UniverseFilter.Compare(CatalogField.CONTRACT_SIZE, CompareOp.GT, CatalogValue.Num(BigDecimal(5))),
            ),
        )
        assertThat(spec.top).isEqualTo(50)
        assertThat(spec.by).isEqualTo(CatalogField.SYMBOL)
        assertThat(spec.descending).isTrue()
        assertThat(spec.canonical)
            .isEqualTo("ALPACA:* WHERE (category = \"us_equity\" AND contract_size > 5) TOP 50 BY symbol DESC")
        assertThat(SymbolSetSpec.parse(spec.canonical)).isEqualTo(spec)
        assertThat(filtered("X:* WHERE kind = spot TOP 3 BY dte ASC").descending).isFalse()
        assertThat(filtered("X:* WHERE kind = spot TOP 3 BY dte desc").descending).isTrue()
    }

    @Test
    fun `every comparison, IN, GLOB, NOT, OR and parentheses parse and round-trip`() {
        val spec =
            filtered(
                "X:* WHERE (kind == spot OR kind <> \"equity\") AND NOT category GLOB \"Stocks*\" " +
                    "AND currency IN (USD, \"EUR\") AND dte >= -1.5 AND dte <= 30 AND contract_size < 1e3 TOP 10 BY contract_size",
            )
        val expected =
            UniverseFilter.And(
                UniverseFilter.And(
                    UniverseFilter.And(
                        UniverseFilter.And(
                            UniverseFilter.And(
                                UniverseFilter.Or(
                                    UniverseFilter.Compare(CatalogField.KIND, CompareOp.EQ, CatalogValue.Str("spot")),
                                    UniverseFilter.Compare(CatalogField.KIND, CompareOp.NE, CatalogValue.Str("equity")),
                                ),
                                UniverseFilter.Not(UniverseFilter.Glob(CatalogField.CATEGORY, "Stocks*")),
                            ),
                            UniverseFilter.OneOf(
                                CatalogField.CURRENCY,
                                listOf(CatalogValue.Str("USD"), CatalogValue.Str("EUR")),
                            ),
                        ),
                        UniverseFilter.Compare(CatalogField.DTE, CompareOp.GE, CatalogValue.Num(BigDecimal("-1.5"))),
                    ),
                    UniverseFilter.Compare(CatalogField.DTE, CompareOp.LE, CatalogValue.Num(BigDecimal(30))),
                ),
                UniverseFilter.Compare(CatalogField.CONTRACT_SIZE, CompareOp.LT, CatalogValue.Num(BigDecimal(1000))),
            )
        assertThat(spec.filter).isEqualTo(expected)
        assertThat(SymbolSetSpec.parse(spec.canonical)).isEqualTo(spec)
        assertThat(filtered("X:* WHERE kind != spot TOP 1 BY symbol").filter)
            .isEqualTo(UniverseFilter.Compare(CatalogField.KIND, CompareOp.NE, CatalogValue.Str("spot")))
    }

    @Test
    fun `a union lists its operands`() {
        val spec = SymbolSetSpec.parse("fx + crypto+metals")
        assertThat(spec).isEqualTo(SymbolSetSpec.Union(listOf("fx", "crypto", "metals")))
        assertThat(spec.canonical).isEqualTo("fx + crypto + metals")
        assertThat(SymbolSetSpec.parse(spec.canonical)).isEqualTo(spec)
    }

    @Test
    fun `of reads a SET stream and ignores every other stream`() {
        val set = StreamDecl("fx", SET_BROKER, "EXNESS:{EURUSD}", "1h")
        assertThat(SymbolSetSpec.of(set)).isEqualTo(SymbolSetSpec.Picked("EXNESS", listOf("EURUSD")))
        assertThat(SymbolSetSpec.of(StreamDecl("gold", "EXNESS", "XAUUSD", "1h"))).isNull()
    }

    @Test
    fun `malformed specs are refused with the reason`() {
        fun refuse(
            text: String,
            reason: String,
        ) {
            assertThatThrownBy { SymbolSetSpec.parse(text) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(reason)
        }
        refuse("EXNESS:{}", "expected symbol after ':'")
        refuse("EXNESS:{EURUSD, EURUSD}", "'EURUSD' is listed twice in the set")
        refuse("EXNESS:{EURUSD XAUUSD}", "expected ',' or '}' in the symbol list")
        refuse("EXNESS:EURUSD", "expected '{' or '*' after 'EXNESS:'")
        refuse("EXNESS", "expected ':' after the venue")
        refuse("EXNESS:* kind = spot TOP 1 BY symbol", "expected WHERE after 'EXNESS:*'")
        refuse("X:* WHERE open_interest > 1 TOP 1 BY symbol", "unknown catalog field 'open_interest'; use symbol, kind")
        refuse("X:* WHERE kind spot TOP 1 BY symbol", "expected a comparison, IN or GLOB after 'kind'")
        refuse("X:* WHERE kind = spot BY symbol", "expected TOP <n> after the WHERE filter")
        refuse("X:* WHERE kind = spot TOP 0 BY symbol", "TOP needs a positive whole number, got '0'")
        refuse("X:* WHERE kind = spot TOP 1.5 BY symbol", "TOP needs a positive whole number, got '1.5'")
        refuse("X:* WHERE kind = spot TOP 1 symbol", "expected BY <field> after TOP 1")
        refuse("X:* WHERE kind = spot TOP 1 BY symbol extra", "unexpected 'extra' after the set")
        refuse("X:* WHERE (kind = spot TOP 1 BY symbol", "expected ')' to close the filter group")
        refuse("X:* WHERE kind IN spot TOP 1 BY symbol", "expected '(' after IN")
        refuse("X:* WHERE kind IN (spot TOP 1 BY symbol", "expected ')' to close the IN list")
        refuse("X:* WHERE category GLOB Stocks TOP 1 BY symbol", "expected a quoted glob pattern after GLOB")
        refuse("X:* WHERE dte > - TOP 1 BY symbol", "expected a number after '-'")
        refuse("X:* WHERE dte > ) TOP 1 BY symbol", "expected a number, a quoted string or a word")
        refuse("fx + ", "expected a set alias after '+'")
        refuse("X:* WHERE kind = 'unterminated TOP 1 BY symbol", "Unterminated string")
    }
}
