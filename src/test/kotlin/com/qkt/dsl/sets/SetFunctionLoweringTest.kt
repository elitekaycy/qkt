package com.qkt.dsl.sets

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SetFunctionLoweringTest {
    private fun parse(src: String): StrategyAst =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> r.value
            is ParseResult.Failure -> error("parse failed: ${r.errors}")
        }

    private fun errors(src: String): List<String> =
        (Dsl.parse(src) as ParseResult.Failure).errors.map { "${it.line}: ${it.message}" }

    private fun strategy(rules: String): String =
        """
        |STRATEGY s VERSION 1
        |SYMBOLS
        |    fx = EXNESS:{EURUSD, XAUUSD} EVERY 15m
        |    crypto = BINANCE:{BTCUSDT} EVERY 15m
        |    majors = fx + crypto
        |    stocks = ALPACA:* WHERE kind = equity TOP 5 BY symbol EVERY 15m
        |    gold = EXNESS:XAUUSD EVERY 15m
        |RULES
        |$rules
        """.trimMargin()

    private fun close(alias: String) = StreamFieldRef(alias, "close")

    private fun lhs(rule: Any): Any = ((rule as WhenThen).cond as CmpOp).lhs

    @Test
    fun `RANK_OF over the loop's own set ranks this member's value among every member's`() {
        val ast = parse(strategy("    FOR EACH m IN fx DO WHEN RANK_OF(m.close, fx) = 1 THEN BUY m SIZING 1"))
        assertThat(ast.rules).hasSize(2)
        assertThat(lhs(ast.rules[0]))
            .isEqualTo(FuncCall("RANK_OF.SET", listOf(close("fx.EURUSD"), close("fx.EURUSD"), close("fx.XAUUSD"))))
        assertThat(lhs(ast.rules[1]))
            .isEqualTo(FuncCall("RANK_OF.SET", listOf(close("fx.XAUUSD"), close("fx.EURUSD"), close("fx.XAUUSD"))))
        assertThat(((ast.rules[1] as WhenThen).action as Buy).stream).isEqualTo("fx.XAUUSD")
    }

    @Test
    fun `aggregates take one value per member, any case, over a union or a set other than the loop's`() {
        val ast =
            parse(
                strategy(
                    "    FOR EACH m IN fx DO WHEN m.close > avg_of(m.close, majors) + Sum_Of(m.close, crypto) " +
                        "THEN BUY m SIZING MAX_OF(m.close, fx) / MIN_OF(m.close, fx)",
                ),
            )
        val rule = ast.rules[0] as WhenThen
        assertThat(rule.toString())
            .contains(
                "FuncCall(name=AVG_OF.SET, args=[${close("fx.EURUSD")}, ${close("fx.XAUUSD")}, " +
                    "${close("crypto.BTCUSDT")}])",
            ).contains("FuncCall(name=SUM_OF.SET, args=[${close("crypto.BTCUSDT")}])")
            .contains("FuncCall(name=MAX_OF.SET, args=[${close("fx.EURUSD")}, ${close("fx.XAUUSD")}])")
            .contains("FuncCall(name=MIN_OF.SET")
    }

    @Test
    fun `COUNT_OF counts a test per member`() {
        val ast =
            parse(strategy("    FOR EACH m IN fx DO WHEN COUNT_OF(m.close > m.open, fx) >= 2 THEN BUY m SIZING 1"))
        assertThat((lhs(ast.rules[0]) as FuncCall).name).isEqualTo("COUNT_OF.SET")
        assertThat((lhs(ast.rules[0]) as FuncCall).args).hasSize(2)
    }

    @Test
    fun `a call over a name that is no set, or outside FOR EACH, is left for the compiler to refuse`() {
        val ast =
            parse(
                strategy(
                    "    FOR EACH m IN fx DO WHEN SUM_OF(m.close, gold) > 0 THEN BUY m SIZING 1\n" +
                        "    WHEN SUM_OF(gold.close, fx) > 0 THEN BUY gold SIZING 1\n" +
                        "    FOR EACH m IN fx DO WHEN SUM_OF() > 0 THEN BUY m SIZING 1",
                ),
            )
        assertThat(
            lhs(ast.rules[0]),
        ).isEqualTo(
            FuncCall(
                "SUM_OF",
                listOf(
                    close("fx.EURUSD"),
                    com.qkt.dsl.ast
                        .Ref("gold"),
                ),
            ),
        )
        assertThat((lhs(ast.rules[2]) as FuncCall).name).isEqualTo("SUM_OF")
        assertThat(lhs(ast.rules[4])).isEqualTo(FuncCall("SUM_OF", emptyList()))
    }

    @Test
    fun `a call that cannot mean one value per member is refused at its rule`() {
        fun refusal(rule: String) = errors(strategy("    FOR EACH m IN fx DO WHEN $rule THEN BUY m SIZING 1")).single()
        assertThat(refusal("SUM_OF(m.close, m.open, fx) > 0"))
            .isEqualTo("9: SUM_OF(…, fx): takes one expression and the set, got 3 arguments")
        assertThat(refusal("SUM_OF(gold.close, fx) > 0"))
            .isEqualTo("9: SUM_OF(…, fx): the expression must use the loop variable 'm', once per member")
        assertThat(refusal("RANK_OF(m.close - AVG_OF(m.close, fx), fx) = 1"))
            .isEqualTo("9: RANK_OF(…, fx): a cross-member function cannot be nested inside another")
        assertThat(refusal("COUNT_OF(m.close, fx) > 1"))
            .isEqualTo("9: COUNT_OF(…, fx): COUNT_OF counts the members where a test holds: compare the value")
    }

    @Test
    fun `a set function over a set that resolves at another time than the loop's set is refused`() {
        val pickedLoop =
            errors(strategy("    FOR EACH m IN fx DO WHEN RANK_OF(m.close, stocks) = 1 THEN BUY m SIZING 1"))
        assertThat(pickedLoop.single()).startsWith("9: FOR EACH m IN fx: 'stocks' and 'fx' resolve at different times")
        val filteredLoop =
            errors(strategy("    FOR EACH m IN stocks DO WHEN RANK_OF(m.close, fx) = 1 THEN BUY m SIZING 1"))
        assertThat(filteredLoop.single()).startsWith("9: FOR EACH m IN stocks: 'fx' and 'stocks' resolve at different")
    }

    @Test
    fun `a filtered loop keeps its template until its members are known, then lowers over them`() {
        val ast = parse(strategy("    FOR EACH m IN stocks DO WHEN RANK_OF(m.close, stocks) = 1 THEN BUY m SIZING 1"))
        val loop = ast.rules.single() as ForEachSet
        assertThat(lhs(loop.template)).isEqualTo(
            FuncCall(
                "RANK_OF",
                listOf(
                    close("m"),
                    com.qkt.dsl.ast
                        .Ref("stocks"),
                ),
            ),
        )
        val expanded = SymbolSetExpansion.expand(ast.streams, ast.rules) { _, _ -> listOf("AAPL", "MSFT") }
        assertThat(expanded.rules.map(::lhs)).containsExactly(
            FuncCall("RANK_OF.SET", listOf(close("stocks.AAPL"), close("stocks.AAPL"), close("stocks.MSFT"))),
            FuncCall("RANK_OF.SET", listOf(close("stocks.MSFT"), close("stocks.AAPL"), close("stocks.MSFT"))),
        )
    }

    @Test
    fun `lowered names cannot be written and name their function`() {
        assertThat(SetFunction.lowered("SUM_OF.SET")).isEqualTo(SetFunction.SUM_OF)
        assertThat(SetFunction.lowered("SUM_OF")).isNull()
        assertThat(SetFunction.named("count_of")).isEqualTo(SetFunction.COUNT_OF)
        assertThat(SetFunction.named("ABS")).isNull()
        assertThat(SetFunction.entries.filter { it.selfFirst }).containsExactly(SetFunction.RANK_OF)
    }
}
