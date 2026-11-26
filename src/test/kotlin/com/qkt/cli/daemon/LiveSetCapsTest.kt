package com.qkt.cli.daemon

import com.qkt.cli.PerStrategyRisk
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.sets.DynamicSetFixtures
import com.qkt.dsl.sets.DynamicSetFixtures.MINUTE
import com.qkt.dsl.sets.DynamicSetFixtures.T0
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.risk.SetCaps
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A live set cap counts the set's active members at each check, as its membership moves. */
class LiveSetCapsTest {
    private val plan = DynamicSetFixtures.plan()
    private val host = plan.strategy(emptyMap(), live = true) as DynamicSetHost
    private val risk = PerStrategyRisk(sets = mapOf("s" to SetCaps(maxOpenPositions = 1)))

    @Test
    fun `a moving set's cap reads its active members, sticky ones included, at each check`() {
        val caps = liveLimits(plan.ast, risk, host)?.sets?.getValue("s")
        assertThat(setCaps(host).keys).containsExactly("s")
        assertThat(caps?.current()).isEmpty()
        val membership = host.hosted.single().membership
        membership.at(T0) { emptySet() }
        assertThat(caps?.current()).containsExactly("BACKTEST:A")
        membership.at(T0 + 4 * MINUTE) { setOf("A") }
        assertThat(caps?.current()).containsExactly("BACKTEST:C", "BACKTEST:B", "BACKTEST:A")
    }

    @Test
    fun `a strategy without a moving set has no set suppliers, and no risk block no limits`() {
        val text =
            "STRATEGY p VERSION 1\nSYMBOLS\n    x = BACKTEST:A EVERY 1m\n" +
                "RULES\n    WHEN x.close > 1 THEN BUY x SIZING 1"
        val plain = AstCompiler().compile(DynamicSetFixtures.ast(text))
        assertThat(setCaps(plain)).isEmpty()
        assertThat(liveLimits(plan.ast, null, host)).isNull()
    }
}
