package com.qkt.dsl.ast

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParamDeclTest {
    @Test
    fun `holds a literal value`() {
        val p = ParamDecl("riskPct", NumLit(BigDecimal("0.01")))
        assertThat(p.name).isEqualTo("riskPct")
        assertThat(p.value).isEqualTo(NumLit(BigDecimal("0.01")))
    }

    @Test
    fun `rejects a non-literal value`() {
        assertThatThrownBy { ParamDecl("x", IndicatorCall("ema", emptyList())) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PARAM")
    }

    @Test
    fun `rejects a blank name`() {
        assertThatThrownBy { ParamDecl("", NumLit(BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `StrategyAst rejects a param colliding with a let`() {
        assertThatThrownBy {
            StrategyAst(
                name = "s",
                version = 1,
                streams = emptyList(),
                constants = emptyList(),
                lets = listOf(LetDecl("x", NumLit(BigDecimal.ONE))),
                params = listOf(ParamDecl("x", NumLit(BigDecimal.TEN))),
                defaults = null,
                rules = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("x")
    }
}
