package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.dsl.ast.SessionWindow
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionWindowEvalTest {
    private fun ec(clock: FixedClock): EvalContext =
        EvalContext(
            candle =
                Candle(
                    symbol = "BACKTEST:XAUUSD",
                    open = BigDecimal.ZERO,
                    high = BigDecimal.ZERO,
                    low = BigDecimal.ZERO,
                    close = BigDecimal.ZERO,
                    volume = BigDecimal.ZERO,
                    startTime = clock.now(),
                    endTime = clock.now() + 60_000L,
                ),
            streams = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(clock = clock),
        )

    /** Evaluate [win] as if "now" were [isoTime] UTC (e.g. "2026-05-11T00:45:00Z"). */
    private fun hits(
        win: SessionWindow,
        isoTime: String,
    ): Boolean {
        val ms = Instant.parse(isoTime).toEpochMilli()
        return (ExprCompiler().compile(win).evaluate(ec(FixedClock(time = ms))) as Value.Bool).v
    }

    // Asian-open window 00:30-01:30 UTC (does not wrap midnight).
    private val asian = SessionWindow(startHour = 0, startMinute = 30, endHour = 1, endMinute = 30)

    // A window that wraps midnight: 23:00-01:00 UTC.
    private val overnight = SessionWindow(startHour = 23, startMinute = 0, endHour = 1, endMinute = 0)

    @Test
    fun `non-wrapping window matches times inside it`() {
        assertThat(hits(asian, "2026-05-11T00:45:00Z")).isTrue
        assertThat(hits(asian, "2026-05-11T01:00:00Z")).isTrue
    }

    @Test
    fun `non-wrapping window excludes times outside it`() {
        assertThat(hits(asian, "2026-05-11T00:29:00Z")).isFalse
        assertThat(hits(asian, "2026-05-11T01:31:00Z")).isFalse
        assertThat(hits(asian, "2026-05-11T13:00:00Z")).isFalse
    }

    @Test
    fun `non-wrapping window is inclusive of both boundary minutes`() {
        assertThat(hits(asian, "2026-05-11T00:30:00Z")).isTrue
        assertThat(hits(asian, "2026-05-11T01:30:00Z")).isTrue
    }

    @Test
    fun `wrapping window matches times on both sides of midnight`() {
        assertThat(hits(overnight, "2026-05-11T23:30:00Z")).isTrue
        assertThat(hits(overnight, "2026-05-11T00:30:00Z")).isTrue
    }

    @Test
    fun `wrapping window excludes times in the gap and is inclusive at the edges`() {
        assertThat(hits(overnight, "2026-05-11T22:30:00Z")).isFalse
        assertThat(hits(overnight, "2026-05-11T01:30:00Z")).isFalse
        assertThat(hits(overnight, "2026-05-11T23:00:00Z")).isTrue
        assertThat(hits(overnight, "2026-05-11T01:00:00Z")).isTrue
    }
}
