package com.qkt.dsl.parse

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.TimeTightenAst
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParserStopRatchetTest {
    @Test
    fun `stepped stop parses ordered breakeven and entry targets`() {
        val source =
            """
            BUY x BRACKET {
              STOP LOSS BY 50
                STEP TO BREAKEVEN AFTER MFE >= 30
                STEP TO ENTRY + 40 AFTER MFE >= 70,
              TAKE PROFIT BY 120
            }
            """.trimIndent()

        val stop = ((Parser(Lexer(source).tokenize()).parseAction() as Buy).opts.bracket?.stopLoss as ChildBy)
        val ratchet = stop.ratchet as SteppedStopAst

        assertThat((stop.distance as NumLit).value).isEqualByComparingTo("50")
        assertThat(ratchet.steps).hasSize(2)
        assertThat((ratchet.steps[0].profitDistance as NumLit).value).isEqualByComparingTo("0")
        assertThat((ratchet.steps[0].mfeThreshold as NumLit).value).isEqualByComparingTo("30")
        assertThat((ratchet.steps[1].profitDistance as NumLit).value).isEqualByComparingTo("40")
        assertThat((ratchet.steps[1].mfeThreshold as NumLit).value).isEqualByComparingTo("70")
    }

    @Test
    fun `time tightening stop parses its interval and floor`() {
        val source =
            "BUY x BRACKET { STOP LOSS BY 60 TIGHTEN BY 10 EVERY 15m FLOOR 20, TAKE PROFIT BY 120 }"

        val stop = ((Parser(Lexer(source).tokenize()).parseAction() as Buy).opts.bracket?.stopLoss as ChildBy)
        val ratchet = stop.ratchet as TimeTightenAst

        assertThat((stop.distance as NumLit).value).isEqualByComparingTo("60")
        assertThat((ratchet.tightenBy as NumLit).value).isEqualByComparingTo("10")
        assertThat(ratchet.interval.millis).isEqualTo(900_000L)
        assertThat((ratchet.floorDistance as NumLit).value).isEqualByComparingTo("20")
    }

    @Test
    fun `step target must name breakeven or entry`() {
        val source =
            "BUY x BRACKET { STOP LOSS BY 50 STEP TO PROFIT AFTER MFE >= 30, TAKE PROFIT BY 120 }"

        assertThatThrownBy { Parser(Lexer(source).tokenize()).parseAction() }
            .hasMessageContaining("BREAKEVEN or ENTRY")
    }
}
