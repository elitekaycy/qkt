package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AggregateStateTest {
    @Test
    fun `sinceOpen MAX tracks running max`() {
        val s = AggregateState.sinceOpen(AggFn.MAX)
        assertThat(s.read()).isNull()
        s.update(BigDecimal("3"))
        s.update(BigDecimal("7"))
        s.update(BigDecimal("5"))
        assertThat(s.read()).isEqualByComparingTo("7")
    }

    @Test
    fun `sinceOpen MIN tracks running min`() {
        val s = AggregateState.sinceOpen(AggFn.MIN)
        s.update(BigDecimal("3"))
        s.update(BigDecimal("1"))
        s.update(BigDecimal("5"))
        assertThat(s.read()).isEqualByComparingTo("1")
    }

    @Test
    fun `sinceOpen SUM and MEAN`() {
        val s = AggregateState.sinceOpen(AggFn.SUM)
        s.update(BigDecimal("1"))
        s.update(BigDecimal("2"))
        s.update(BigDecimal("3"))
        assertThat(s.read()).isEqualByComparingTo("6")
        val m = AggregateState.sinceOpen(AggFn.MEAN)
        m.update(BigDecimal("1"))
        m.update(BigDecimal("2"))
        m.update(BigDecimal("3"))
        assertThat(m.read()).isEqualByComparingTo("2")
    }

    @Test
    fun `sinceOpen reset clears`() {
        val s = AggregateState.sinceOpen(AggFn.MAX)
        s.update(BigDecimal("7"))
        s.reset()
        assertThat(s.read()).isNull()
        s.update(BigDecimal("2"))
        assertThat(s.read()).isEqualByComparingTo("2")
    }

    @Test
    fun `sinceT requires N samples`() {
        val s = AggregateState.sinceT(AggFn.MAX, n = 3)
        assertThat(s.read()).isNull()
        s.update(BigDecimal("1"))
        s.update(BigDecimal("2"))
        assertThat(s.read()).isNull()
        s.update(BigDecimal("9"))
        assertThat(s.read()).isEqualByComparingTo("9")
    }

    @Test
    fun `sinceT slides window`() {
        val s = AggregateState.sinceT(AggFn.MAX, n = 3)
        s.update(BigDecimal("9"))
        s.update(BigDecimal("1"))
        s.update(BigDecimal("2"))
        assertThat(s.read()).isEqualByComparingTo("9")
        s.update(BigDecimal("3"))
        assertThat(s.read()).isEqualByComparingTo("3")
    }
}
