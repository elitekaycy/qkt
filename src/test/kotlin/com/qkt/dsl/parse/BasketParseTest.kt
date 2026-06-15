package com.qkt.dsl.parse

import com.qkt.dsl.ast.BasketWeighting
import com.qkt.dsl.ast.StrategyAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BasketParseTest {
    private fun parse(s: String): ParseResult<StrategyAst> = Parser(Lexer(s).tokenize()).parseStrategy()

    @Test
    fun `parses a basket alongside real streams`() {
        val r =
            parse(
                """
                STRATEGY pairs VERSION 1
                SYMBOLS
                    gold = EXNESS:XAUUSD EVERY 1h
                    aud  = EXNESS:AUDUSD EVERY 1h
                    nzd  = EXNESS:NZDUSD EVERY 1h
                    antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
                RULES
                    WHEN gold.close > 0 THEN BUY gold SIZING 0.1
                """.trimIndent(),
            ) as ParseResult.Success

        assertThat(r.value.streams.map { it.alias }).containsExactly("gold", "aud", "nzd")
        assertThat(r.value.baskets).hasSize(1)
        val b = r.value.baskets.single()
        assertThat(b.alias).isEqualTo("antipodean")
        assertThat(b.weighting).isEqualTo(BasketWeighting.EqualWeight)
        assertThat(b.constituents).containsExactly("aud", "nzd")
        assertThat(b.timeframe).isEqualTo("1h")
    }

    @Test
    fun `parses a three-constituent basket`() {
        val r =
            parse(
                """
                STRATEGY pairs VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    nzd = EXNESS:NZDUSD EVERY 1h
                    cad = EXNESS:USDCAD EVERY 1h
                    commod = BASKET EQUAL_WEIGHT [aud, nzd, cad] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN BUY aud SIZING 0.1
                """.trimIndent(),
            ) as ParseResult.Success

        assertThat(
            r.value.baskets
                .single()
                .constituents,
        ).containsExactly("aud", "nzd", "cad")
    }

    @Test
    fun `basket without opening bracket errors`() {
        val r =
            parse(
                """
                STRATEGY pairs VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    nzd = EXNESS:NZDUSD EVERY 1h
                    bad = BASKET EQUAL_WEIGHT aud, nzd ] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN BUY aud SIZING 0.1
                """.trimIndent(),
            ) as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }

    @Test
    fun `single-constituent basket errors`() {
        val r =
            parse(
                """
                STRATEGY pairs VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    bad = BASKET EQUAL_WEIGHT [aud] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN BUY aud SIZING 0.1
                """.trimIndent(),
            ) as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }

    @Test
    fun `basket with trailing comma errors`() {
        val r =
            parse(
                """
                STRATEGY pairs VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    nzd = EXNESS:NZDUSD EVERY 1h
                    bad = BASKET EQUAL_WEIGHT [aud, nzd,] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN BUY aud SIZING 0.1
                """.trimIndent(),
            ) as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }

    @Test
    fun `basket with empty constituent list errors`() {
        val r =
            parse(
                """
                STRATEGY pairs VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    bad = BASKET EQUAL_WEIGHT [] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN BUY aud SIZING 0.1
                """.trimIndent(),
            ) as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }

    @Test
    fun `basket with unknown weighting word errors`() {
        val r =
            parse(
                """
                STRATEGY pairs VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    nzd = EXNESS:NZDUSD EVERY 1h
                    bad = BASKET CASH_WEIGHT [aud, nzd] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN BUY aud SIZING 0.1
                """.trimIndent(),
            ) as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }
}
