package com.qkt.cli.daemon.portfolio

import com.qkt.persistence.PersistedRiskState
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.FakePnL
import com.qkt.risk.HaltScope
import com.qkt.risk.TestClock
import com.qkt.risk.rules.MaxDrawdown
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** #1640: a book whose children cannot be read is held off new entries, never latched for good. */
class PortfolioRiskAggregatorUnreadableTest : PortfolioRiskAggregatorFixture() {
    private val clock = TestClock(0L)
    private val pnl = FakePnL(BigDecimal.ZERO, BigDecimal.ZERO)
    private val saved = mutableListOf<PersistedRiskState>()
    private val book = bookRiskState(pnl, clock) { saved += it }
    private val a = FakeChild()
    private val b = FakeChild()
    private val totalDrawdown = MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("100000"))
    private val agg = PortfolioRiskAggregator(listOf(a, b), book, listOf(totalDrawdown), clock)

    private val unreadable =
        "portfolio risk evaluation failed 3 consecutive times: " +
            "live engine did not produce a consistent snapshot within the timeout"

    @Test
    fun `an unreadable book refuses entries on every child without closing positions or halting for good`() {
        agg.failClosed(unreadable)
        agg.failClosed(unreadable)

        assertThat(listOf(a.halted, b.halted)).containsOnly(unreadable)
        assertThat(listOf(a.scope, b.scope)).containsOnly(HaltScope.TRANSIENT)
        assertThat(listOf(a.flattened, b.flattened)).containsOnly(0)
        assertThat(book.halted).isFalse()
        assertThat(saved.none { it.halted }).isTrue()
        assertThat(agg.bookHalt()?.scope).isEqualTo(HaltScope.TRANSIENT)
    }

    @Test
    fun `the hold clears on the first evaluation that reads the book again`() {
        agg.failClosed(unreadable)

        agg.evaluate()

        assertThat(listOf(a.halted, b.halted)).containsOnly(null)
        assertThat(listOf(a.resumed, b.resumed)).containsOnly(1)
        assertThat(agg.bookHalt()).isNull()
    }

    @Test
    fun `a child halted for its own reason is left halted when the book reads again`() {
        a.halt("engine fault", HaltScope.PERSISTENT)
        agg.failClosed(unreadable)

        agg.evaluate()

        assertThat(a.halted).isEqualTo("engine fault")
        assertThat(a.resumed).isEqualTo(0)
        assertThat(b.halted).isNull()
    }

    @Test
    fun `a real breach found once the book reads again still closes and halts persistently`() {
        agg.failClosed(unreadable)
        pnl.realized = BigDecimal("-9000")

        agg.evaluate()

        assertThat(listOf(a.scope, b.scope)).containsOnly(HaltScope.PERSISTENT)
        assertThat(listOf(a.flattened, b.flattened)).containsOnly(1)
        assertThat(book.halted).isTrue()
    }
}
