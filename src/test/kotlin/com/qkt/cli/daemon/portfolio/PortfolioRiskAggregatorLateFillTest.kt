package com.qkt.cli.daemon.portfolio

import com.qkt.risk.FakePnL
import com.qkt.risk.HaltScope
import com.qkt.risk.TestClock
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** #1608: an entry in flight when the book halts fills after the book's close, and must not stay open. */
class PortfolioRiskAggregatorLateFillTest : PortfolioRiskAggregatorFixture() {
    private val clock = TestClock(0L)
    private val child = FakeChild()
    private val book = bookRiskState(FakePnL(BigDecimal.ZERO, BigDecimal.ZERO), clock)
    private val aggregator = PortfolioRiskAggregator(listOf(child), book, emptyList(), clock)

    private fun haltBook() {
        book.halt("breach", HaltScope.PERSISTENT)
        aggregator.evaluate()
    }

    private fun beatAfter(ms: Long) {
        clock.t += ms
        aggregator.evaluate()
    }

    @Test
    fun `an entry filling after the halt's close is closed`() {
        haltBook()
        assertThat(child.flattened).isEqualTo(1)

        child.unclosedLegs = 1
        beatAfter(5_000)

        assertThat(child.flattened).isEqualTo(2)
    }

    @Test
    fun `a close still working is never doubled`() {
        haltBook()
        child.unclosedLegs = 0 // the leg is open, its close is out: the session does not count it

        repeat(20) { beatAfter(5_000) }

        assertThat(child.flattened).isEqualTo(1)
    }

    @Test
    fun `a leg is left a moment for the first close to reach it before closing again`() {
        haltBook()
        child.unclosedLegs = 1

        beatAfter(1_000)

        assertThat(child.flattened).isEqualTo(1)
    }

    @Test
    fun `a close the venue keeps refusing backs off and is left to the operator`() {
        haltBook()
        child.unclosedLegs = 1

        repeat(200) { beatAfter(5_000) }

        assertThat(child.flattened).isEqualTo(1 + LateFillCloser.MAX_CLOSES)
    }

    @Test
    fun `once flat, a later fill gets its own closes`() {
        haltBook()
        child.unclosedLegs = 1
        repeat(200) { beatAfter(5_000) }
        child.unclosedLegs = 0
        beatAfter(5_000)
        val before = child.flattened

        child.unclosedLegs = 1
        beatAfter(5_000)

        assertThat(child.flattened).isEqualTo(before + 1)
    }

    @Test
    fun `a child an operator resumed on its own is left to trade`() {
        haltBook()
        child.resume()
        child.unclosedLegs = 1

        beatAfter(60_000)

        assertThat(child.flattened).isEqualTo(1)
    }

    @Test
    fun `a child that cannot answer is asked again without failing the book's evaluation`() {
        haltBook()
        child.unclosedLegs = 1
        child.answers = false

        beatAfter(60_000)
        assertThat(child.flattened).isEqualTo(1)

        child.answers = true
        beatAfter(1_000)
        assertThat(child.flattened).isEqualTo(2)
        assertThat(child.scope).isEqualTo(HaltScope.PERSISTENT)
    }
}
