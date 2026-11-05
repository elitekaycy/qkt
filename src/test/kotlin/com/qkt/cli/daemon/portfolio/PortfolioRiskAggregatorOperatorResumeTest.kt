package com.qkt.cli.daemon.portfolio

import com.qkt.persistence.PersistedRiskState
import com.qkt.risk.FakePnL
import com.qkt.risk.HaltScope
import com.qkt.risk.TestClock
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** #1640: a persistent book halt, restored across restarts, is cleared by the operator and only by the operator. */
class PortfolioRiskAggregatorOperatorResumeTest : PortfolioRiskAggregatorFixture() {
    private val clock = TestClock(0L)
    private val saved = mutableListOf<PersistedRiskState>()
    private val book = bookRiskState(FakePnL(BigDecimal.ZERO, BigDecimal.ZERO), clock) { saved += it }
    private val a = FakeChild()
    private val b = FakeChild()
    private val agg = PortfolioRiskAggregator(listOf(a, b), book, emptyList(), clock)
    private val reason = "portfolio risk evaluation failed 3 consecutive times: busy"

    @Test
    fun `a restored persistent book halt is reported and stays across days`() {
        book.halt(reason, HaltScope.PERSISTENT)
        agg.evaluate()
        clock.t = 3 * DAY_MS
        agg.evaluate()

        assertThat(agg.bookHalt()?.reason).isEqualTo(reason)
        assertThat(agg.bookHalt()?.scope).isEqualTo(HaltScope.PERSISTENT)
        assertThat(listOf(a.halted, b.halted)).containsOnly(reason)
    }

    @Test
    fun `operator resume clears the book halt and the halts it put on children`() {
        book.halt(reason, HaltScope.PERSISTENT)
        agg.evaluate()

        val cleared = agg.resumeByOperator()

        assertThat(cleared?.reason).isEqualTo(reason)
        assertThat(book.halted).isFalse()
        assertThat(saved.last().halted).isFalse()
        assertThat(listOf(a.halted, b.halted)).containsOnly(null)
        assertThat(agg.bookHalt()).isNull()
        agg.evaluate()
        assertThat(listOf(a.halted, b.halted)).containsOnly(null)
    }

    @Test
    fun `operator resume before the first evaluation still clears children restored with the book's halt`() {
        book.halt(reason, HaltScope.PERSISTENT)
        a.halt(reason, HaltScope.PERSISTENT)
        b.halt("engine fault", HaltScope.PERSISTENT)

        agg.resumeByOperator()

        assertThat(a.halted).isNull()
        assertThat(b.halted).isEqualTo("engine fault")
    }

    @Test
    fun `operator resume of a book that is not halted changes nothing`() {
        b.halt("engine fault", HaltScope.PERSISTENT)

        assertThat(agg.resumeByOperator()).isNull()
        assertThat(b.halted).isEqualTo("engine fault")
        assertThat(b.resumed).isEqualTo(0)
    }
}
