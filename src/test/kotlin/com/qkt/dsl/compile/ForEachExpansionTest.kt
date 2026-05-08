package com.qkt.dsl.compile

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.strategy
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForEachExpansionTest {
    @Test
    fun `forEach expands to one rule per stream`() {
        val ast =
            strategy("fe", 1) {
                val a = stream("a", "BACKTEST", "AAA", "1m")
                val b = stream("b", "BACKTEST", "BBB", "1m")
                val c = stream("c", "BACKTEST", "CCC", "1m")
                forEach(a, b, c) { s ->
                    rule {
                        whenever(s.close gt 100.bd)
                        then { buy(stream = s, qty = BigDecimal.ONE.bd) }
                    }
                }
            }
        assertThat(ast.rules).hasSize(3)
        val streams =
            ast.rules
                .map { (it as WhenThen).action }
                .map { (it as Buy).stream }
        assertThat(streams).containsExactly("a", "b", "c")
    }

    @Test
    fun `forEach result equals hand-expanded rules`() {
        val expanded =
            strategy("fe", 1) {
                val a = stream("a", "BACKTEST", "AAA", "1m")
                val b = stream("b", "BACKTEST", "BBB", "1m")
                forEach(a, b) { s ->
                    rule {
                        whenever(s.close gt 100.bd)
                        then { buy(stream = s, qty = BigDecimal.ONE.bd) }
                    }
                }
            }
        val handwritten =
            strategy("fe", 1) {
                val a = stream("a", "BACKTEST", "AAA", "1m")
                val b = stream("b", "BACKTEST", "BBB", "1m")
                rule {
                    whenever(a.close gt 100.bd)
                    then { buy(stream = a, qty = BigDecimal.ONE.bd) }
                }
                rule {
                    whenever(b.close gt 100.bd)
                    then { buy(stream = b, qty = BigDecimal.ONE.bd) }
                }
            }
        assertThat(expanded.rules).isEqualTo(handwritten.rules)
    }

    @Test
    fun `forEach with no streams is rejected`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                strategy("fe", 1) {
                    stream("a", "BACKTEST", "AAA", "1m")
                    forEach { _ -> }
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
