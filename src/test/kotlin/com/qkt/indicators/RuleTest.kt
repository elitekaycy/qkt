package com.qkt.indicators

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuleTest {
    private fun stub(
        value: BigDecimal?,
        ready: Boolean = value != null,
    ) = object : IndicatorOutput {
        override fun value(): BigDecimal? = value

        override val isReady: Boolean = ready
        override val warmupBars: Int = 0
    }

    @Test
    fun `gt is true when left greater than right`() {
        val rule = stub(Money.of("10")) gt stub(Money.of("5"))
        assertThat(rule.evaluate()).isTrue()
    }

    @Test
    fun `gt is false when left less or equal to right`() {
        assertThat((stub(Money.of("5")) gt stub(Money.of("10"))).evaluate()).isFalse()
        assertThat((stub(Money.of("5")) gt stub(Money.of("5"))).evaluate()).isFalse()
    }

    @Test
    fun `lt is true when left less than right and false otherwise`() {
        assertThat((stub(Money.of("3")) lt stub(Money.of("4"))).evaluate()).isTrue()
        assertThat((stub(Money.of("10")) lt stub(Money.of("5"))).evaluate()).isFalse()
        assertThat((stub(Money.of("5")) lt stub(Money.of("5"))).evaluate()).isFalse()
    }

    @Test
    fun `eq is true when values compare equal regardless of scale and false otherwise`() {
        assertThat((stub(BigDecimal("5.00")) eq stub(BigDecimal("5"))).evaluate()).isTrue()
        assertThat((stub(Money.of("5")) eq stub(Money.of("6"))).evaluate()).isFalse()
    }

    @Test
    fun `threshold gt overload compares against BigDecimal`() {
        assertThat((stub(Money.of("10")) gt Money.of("5")).evaluate()).isTrue()
        assertThat((stub(Money.of("3")) gt Money.of("5")).evaluate()).isFalse()
    }

    @Test
    fun `threshold lt overload compares against BigDecimal`() {
        assertThat((stub(Money.of("3")) lt Money.of("5")).evaluate()).isTrue()
        assertThat((stub(Money.of("10")) lt Money.of("5")).evaluate()).isFalse()
    }

    @Test
    fun `and is true only when both rules are true`() {
        val tt = stub(Money.of("10")) gt stub(Money.of("5"))
        val ff = stub(Money.of("1")) gt stub(Money.of("5"))
        assertThat((tt and tt).evaluate()).isTrue()
        assertThat((tt and ff).evaluate()).isFalse()
        assertThat((ff and ff).evaluate()).isFalse()
    }

    @Test
    fun `or is true when at least one rule is true`() {
        val tt = stub(Money.of("10")) gt stub(Money.of("5"))
        val ff = stub(Money.of("1")) gt stub(Money.of("5"))
        assertThat((tt or ff).evaluate()).isTrue()
        assertThat((tt or tt).evaluate()).isTrue()
        assertThat((ff or ff).evaluate()).isFalse()
    }

    @Test
    fun `not inverts a rule`() {
        val tt = stub(Money.of("10")) gt stub(Money.of("5"))
        val ff = stub(Money.of("1")) gt stub(Money.of("5"))
        assertThat((!tt).evaluate()).isFalse()
        assertThat((!ff).evaluate()).isTrue()
    }

    @Test
    fun `evaluates false when any underlying indicator is not ready`() {
        val notReady = stub(value = null, ready = false)
        val ready = stub(Money.of("5"))
        assertThat((notReady gt ready).evaluate()).isFalse()
        assertThat((ready gt notReady).evaluate()).isFalse()
        assertThat((notReady lt ready).evaluate()).isFalse()
        assertThat((ready lt notReady).evaluate()).isFalse()
        assertThat((notReady eq ready).evaluate()).isFalse()
        assertThat((ready eq notReady).evaluate()).isFalse()
        assertThat((notReady gt Money.of("5")).evaluate()).isFalse()
        assertThat((notReady lt Money.of("5")).evaluate()).isFalse()
    }
}
