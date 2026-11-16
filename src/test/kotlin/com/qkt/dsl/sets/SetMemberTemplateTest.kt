package com.qkt.dsl.sets

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SetMemberTemplateTest {
    private fun parse(src: String): StrategyAst = (Dsl.parse(src) as ParseResult.Success).value

    private val ast =
        parse(
            """
            STRATEGY rotation VERSION 1
            SYMBOLS
                movers = BACKTEST:* WHERE kind = spot TOP 3 BY symbol EVERY 1m WARMUP 5 BARS
                others = BACKTEST:* WHERE kind = future TOP 3 BY symbol EVERY 1m
                gold = BACKTEST:XAUUSD EVERY 1m
            SCHEDULE
                AT 09:00 UTC THEN CLOSE gold
            RULES
                FOR EACH m IN movers DO WHEN m.close > gold.close THEN BUY m SIZING 1
                FOR EACH m IN others DO WHEN m.close > 0 THEN BUY m SIZING 1
                WHEN gold.close > 0 THEN BUY gold SIZING 1
            """.trimIndent(),
        )

    @Test
    fun `a member's strategy holds its stream, the plain streams and the loop rules bound to it`() {
        val template = SetMemberTemplate(ast, "movers")
        assertThat(template.alias("AAA")).isEqualTo("movers.AAA")
        val member = template.ast("AAA")
        assertThat(member.streams).containsExactly(
            StreamDecl("movers.AAA", "BACKTEST", "AAA", "1m", 5),
            StreamDecl("gold", "BACKTEST", "XAUUSD", "1m"),
        )
        assertThat(member.rules.map { ((it as WhenThen).action as Buy).stream }).containsExactly("movers.AAA")
        assertThat(member.schedules).isEmpty()
        assertThat(template.compile("AAA").declaredStreams.keys).containsExactlyInAnyOrder("movers.AAA", "gold")
    }

    @Test
    fun `a set that cannot run per member is refused`() {
        assertThatThrownBy { SetMemberTemplate(ast, "gold") }
            .hasMessage("'gold' is not a symbol set declared in SYMBOLS")
        val union =
            parse(
                """
                STRATEGY u VERSION 1
                SYMBOLS
                    a = BACKTEST:{X, Y} EVERY 1m
                    b = BACKTEST:* WHERE kind = spot TOP 3 BY symbol EVERY 1m
                    both = a + b
                    idle = BACKTEST:* WHERE kind = spot TOP 3 BY symbol EVERY 1m
                    ranked = BACKTEST:* WHERE kind = spot TOP 3 BY symbol EVERY 1m
                RULES
                    FOR EACH m IN both DO WHEN m.close > 0 THEN BUY m SIZING 1
                    FOR EACH m IN ranked DO WHEN RANK_OF(m.close, ranked) = 1 THEN BUY m SIZING 1
                """.trimIndent(),
            )
        assertThatThrownBy { SetMemberTemplate(union, "both") }
            .hasMessage("symbol set 'both' is a union; a union's members cannot change during a run")
        assertThatThrownBy { SetMemberTemplate(union, "idle") }
            .hasMessage("symbol set 'idle' has no FOR EACH rule to run per member")
        assertThatThrownBy { SetMemberTemplate(union, "ranked") }
            .hasMessage(
                "FOR EACH m IN ranked: cross-member functions over a set whose members change are not supported yet",
            )
    }
}
