package com.qkt.risk

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunawayBreakerTest {
    @Test
    fun `churn loop halts the strategy persistently`() {
        val clock = TestClock(0L)
        val riskState = RiskState.noOp(clock)
        val breaker = RunawayBreaker(clock, riskState, maxRoundTrips = 5, roundTripWindowMs = 60_000L)

        repeat(5) {
            clock.t += 1_000L
            breaker.recordClose("churner")
        }
        assertThat(riskState.isStrategyHalted("churner")).isFalse()

        clock.t += 1_000L
        breaker.recordClose("churner")
        assertThat(riskState.isStrategyHalted("churner")).isTrue()
        assertThat(riskState.haltReasonFor("churner")).contains("round trips")
        // PERSISTENT: the midnight sweep must NOT clear it — operator resume only.
        clock.t += 86_400_000L
        riskState.clearExpiredDailyHalts()
        assertThat(riskState.isStrategyHalted("churner")).isTrue()
    }

    @Test
    fun `rejection hammering halts the strategy`() {
        val clock = TestClock(0L)
        val riskState = RiskState.noOp(clock)
        val breaker = RunawayBreaker(clock, riskState, maxRejections = 3, rejectionWindowMs = 10_000L)

        repeat(4) {
            clock.t += 100L
            breaker.recordRejection("hammer")
        }
        assertThat(riskState.isStrategyHalted("hammer")).isTrue()
        assertThat(riskState.haltReasonFor("hammer")).contains("rejections")
    }

    @Test
    fun `normal trading spread over time never trips the defaults`() {
        val clock = TestClock(0L)
        val riskState = RiskState.noOp(clock)
        val breaker = RunawayBreaker(clock, riskState)

        // One round trip every 2 minutes for 2 hours — well under 10-per-10-minutes.
        repeat(60) {
            clock.t += 120_000L
            breaker.recordClose("steady")
        }
        assertThat(riskState.isStrategyHalted("steady")).isFalse()
    }

    @Test
    fun `zero threshold disables the counter`() {
        val clock = TestClock(0L)
        val riskState = RiskState.noOp(clock)
        val breaker = RunawayBreaker(clock, riskState, maxRoundTrips = 0)
        repeat(100) { breaker.recordClose("free") }
        assertThat(riskState.isStrategyHalted("free")).isFalse()
    }
}
