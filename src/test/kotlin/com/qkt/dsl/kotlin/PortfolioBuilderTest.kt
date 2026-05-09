package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.WhenRun
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PortfolioBuilderTest {
    @Test
    fun `portfolio builder produces PortfolioAst`() {
        val pf =
            portfolio("p", version = 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                import("a.qkt", alias = "a")
                import("b.qkt", alias = "b", hold = true)
                rules {
                    run("a")
                    whenRun(btc.close gt NumLit(BigDecimal("100")), child = "b")
                }
            }
        assertThat(pf).isInstanceOf(PortfolioAst::class.java)
        assertThat(pf.imports).containsExactly(
            ImportClause("a.qkt", "a", hold = false),
            ImportClause("b.qkt", "b", hold = true),
        )
        assertThat(pf.rules[0]).isEqualTo(AlwaysRun("a"))
        assertThat(pf.rules[1]).isInstanceOf(WhenRun::class.java)
    }

    @Test
    fun `portfolio with no imports throws`() {
        assertThatThrownBy {
            portfolio("p", version = 1) {
                rules { run("x") }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one IMPORT")
    }
}
