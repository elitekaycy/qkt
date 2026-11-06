package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionRight
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LegCriteriaTest {
    private val whole =
        "a leg names a whole days window (minDays and maxDays) or an expiry, not both and not part of a window"

    @Test
    fun `a target delta must lie strictly between zero and one`() {
        assertThatThrownBy { LegCriteria(OptionRight.CALL, 0.0, 1.0, 2.0) }.hasMessageContaining("between 0 and 1: 0.0")
        assertThatThrownBy { LegCriteria(OptionRight.CALL, 1.0, 1.0, 2.0) }.hasMessageContaining("between 0 and 1: 1.0")
    }

    @Test
    fun `a leg names either a whole days window or an expiry`() {
        assertThatThrownBy { LegCriteria(OptionRight.PUT, 0.3, 1.0, 2.0, expiryMs = 5L) }.hasMessage(whole)
        assertThatThrownBy { LegCriteria(OptionRight.PUT, 0.3) }.hasMessage(whole)
        assertThatThrownBy { LegCriteria(OptionRight.PUT, 0.3, minDays = 1.0) }.hasMessage(whole)
        assertThatThrownBy { LegCriteria(OptionRight.PUT, 0.3, maxDays = 1.0, expiryMs = 5L) }.hasMessage(whole)
        assertThat(LegCriteria(OptionRight.PUT, 0.3, expiryMs = 5L).expiryMs).isEqualTo(5L)
        assertThat(LegCriteria(OptionRight.CALL, 0.3, 2.0, 2.0).maxDays).isEqualTo(2.0)
    }
}
