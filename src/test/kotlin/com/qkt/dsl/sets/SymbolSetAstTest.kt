package com.qkt.dsl.sets

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CompileError
import com.qkt.dsl.compile.ExprTransform
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.ParamSubstitution
import com.qkt.dsl.compile.collectMetaRefs
import com.qkt.dsl.compile.rewriteExprs
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SymbolSetAstTest {
    private fun parse(src: String): StrategyAst = (Dsl.parse(src) as ParseResult.Success).value

    private val filtered =
        parse(
            """
            STRATEGY s VERSION 1
            SYMBOLS
                stocks = ALPACA:* WHERE kind = equity TOP 5 BY symbol EVERY 15m
            PARAM n = 30
            RULES
                FOR EACH m IN stocks DO WHEN m.close > n AND m.contract_size > 1 THEN BUY m SIZING n
            """.trimIndent(),
        )

    private val loop = filtered.rules.single() as ForEachSet

    @Test
    fun `member aliases name their set and never clash with a written alias`() {
        assertThat(SetMemberAlias.of("fx", "EURUSD")).isEqualTo("fx.EURUSD")
        assertThat(SetMemberAlias.setOf("fx.EURUSD")).isEqualTo("fx")
        assertThat(SetMemberAlias.setOf("k.KX_B73.5")).isEqualTo("k")
        assertThat(SetMemberAlias.setOf("gold")).isNull()
        assertThat(SetMemberAlias.setOf("cal/surprise")).isNull()
        assertThat(SetMemberAlias.setOf(".odd")).isNull()
        val streams =
            listOf(
                StreamDecl("fx.EURUSD", "EXNESS", "EURUSD", "1h"),
                StreamDecl("fx.XAUUSD", "EXNESS", "XAUUSD", "1h"),
                StreamDecl("crypto.BTCUSDT", "BINANCE", "BTCUSDT", "1h"),
                StreamDecl("gold", "EXNESS", "XAUUSD", "1h"),
            )
        assertThat(SetMemberAlias.membersOf(streams))
            .isEqualTo(mapOf("fx" to setOf("EXNESS:EURUSD", "EXNESS:XAUUSD"), "crypto" to setOf("BINANCE:BTCUSDT")))
    }

    @Test
    fun `ForEachSet compares and prints by its content, not its line`() {
        val template = WhenThen(NumLit(java.math.BigDecimal.ONE), Buy("m"), 9)
        val a = ForEachSet("m", "fx", template, 3)
        val b = ForEachSet("m", "fx", WhenThen(NumLit(java.math.BigDecimal.ONE), Buy("m"), 7), 8)
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b)
        assertThat(a).isNotEqualTo(ForEachSet("x", "fx", template))
        assertThat(a).isNotEqualTo(ForEachSet("m", "fy", template))
        assertThat(a).isNotEqualTo(ForEachSet("m", "fx", WhenThen(NumLit(java.math.BigDecimal.TEN), Buy("m"))))
        assertThat(a).isNotEqualTo(template)
        assertThat(a.toString()).isEqualTo("ForEachSet(iterVar=m, set=fx, template=$template)")
        assertThat(a.line).isEqualTo(3)
        assertThat(ForEachSet("m", "fx", template).line).isZero()
    }

    @Test
    fun `the compiler refuses a strategy whose filtered set was never resolved`() {
        assertThatThrownBy { AstCompiler().compile(filtered) }
            .isInstanceOf(CompileError::class.java)
            .hasMessageContaining("symbol set 'stocks' is a filtered universe, which only a catalog can resolve")
        val loopOnly = filtered.copy(streams = emptyList())
        assertThatThrownBy { AstCompiler().compile(loopOnly) }
            .hasMessageContaining("symbol set 'stocks' is a filtered universe")
        val plain = filtered.copy(streams = listOf(StreamDecl("gold", "EXNESS", "XAUUSD", "1h")), rules = emptyList())
        SymbolSetChecks.requireExpanded(plain)
        assertThat(plain.streams.single().broker).isNotEqualTo(SET_BROKER)
    }

    @Test
    fun `PARAM substitution reaches into an unresolved loop's template`() {
        val substituted = ParamSubstitution.apply(filtered, mapOf("n" to "40")).rules.single() as ForEachSet
        assertThat(substituted.set).isEqualTo("stocks")
        assertThat(substituted.line).isEqualTo(loop.line)
        assertThat(substituted.template.line).isEqualTo(loop.template.line)
        assertThat(substituted.template.toString()).contains("NumLit(value=40)").doesNotContain("Ref(name=n")
    }

    @Test
    fun `expression rewrites and walkers visit an unresolved loop's template`() {
        val renamed =
            rewriteExprs(
                filtered,
                ExprTransform { ref ->
                    if (ref.name ==
                        "n"
                    ) {
                        Ref("k", ref.snapshot)
                    } else {
                        ref
                    }
                },
            )
        val rewritten = renamed.rules.single() as ForEachSet
        assertThat(rewritten.line).isEqualTo(loop.line)
        assertThat(rewritten.template.toString()).contains("Ref(name=k").doesNotContain("Ref(name=n")
        assertThat(((rewritten.template.cond as com.qkt.dsl.ast.BinaryOp).lhs as CmpOp).rhs).isEqualTo(Ref("k"))

        val refs = collectMetaRefs(filtered, mapOf("m" to HubKey("ALPACA", "AAPL", "15m")))
        assertThat(refs.map { it.field }).containsExactly("contract_size")

        val seen = mutableListOf<ExprAst?>()
        com.qkt.cli.requirements
            .ActionExpressionVisitor { seen.add(it) }
            .walkRule(loop)
        assertThat(seen.filterNotNull().joinToString()).contains("field=close").contains("field=contract_size")
    }
}
