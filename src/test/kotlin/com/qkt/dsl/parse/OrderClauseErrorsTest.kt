package com.qkt.dsl.parse

import com.qkt.dsl.ast.BoolLit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Located parse errors for malformed order types, TIFs, latch prices and PARAM literals. */
class OrderClauseErrorsTest {
    private fun parse(rule: String) =
        Dsl.parse(
            "STRATEGY s VERSION 1\nSYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\nPARAM flag = FALSE\nRULES\n    $rule\n",
        )

    private fun errors(rule: String): List<String> = (parse(rule) as ParseResult.Failure).errors.map { it.message }

    @Test
    fun `an unknown order type or trailing form is named`() {
        assertThat(errors("WHEN b.close > 0 THEN BUY b SIZING 1 ORDER_TYPE = BANANA"))
            .anyMatch { it.startsWith("expected order type (MARKET/LIMIT/STOP/TRAILING), got") }
        assertThat(errors("WHEN b.close > 0 THEN BUY b SIZING 1 ORDER_TYPE = TRAILING AT 5"))
            .anyMatch { it.startsWith("expected BY or PCT after TRAILING, got") }
    }

    @Test
    fun `an unknown time in force is named`() {
        assertThat(errors("WHEN b.close > 0 THEN BUY b SIZING 1 TIF NEVER"))
            .anyMatch { it.startsWith("expected TIF (GTC/IOC/FOK/DAY/GTD), got") }
    }

    @Test
    fun `a latch entry price needs a direction word`() {
        assertThat(errors("WHEN b.close > 0 THEN LATCH b OFFSET 1 ARM 5m { ENTER LIMIT AT 5 }"))
            .anyMatch { it.startsWith("expected WITH/AGAINST/RETRACE, got") }
    }

    @Test
    fun `a boolean PARAM literal parses and cannot be negated`() {
        val ok = parse("WHEN flag THEN BUY b SIZING 1") as ParseResult.Success

        assertThat(
            ok.value.params
                .single()
                .value,
        ).isEqualTo(BoolLit(false))
        val negated =
            Dsl.parse(
                "STRATEGY s VERSION 1\nSYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\nPARAM flag = -FALSE\n",
            ) as ParseResult.Failure
        assertThat(negated.errors.map { it.message }).contains("cannot negate a boolean")
    }
}
