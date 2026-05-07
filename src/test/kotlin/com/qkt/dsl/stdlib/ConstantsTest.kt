package com.qkt.dsl.stdlib

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConstantsTest {
    @Test
    fun `named percent constants resolve to expected decimals`() {
        assertThat(Constants.HALF_PERCENT).isEqualByComparingTo("0.005")
        assertThat(Constants.ONE_PERCENT).isEqualByComparingTo("0.01")
        assertThat(Constants.TWO_PERCENT).isEqualByComparingTo("0.02")
        assertThat(Constants.THREE_PERCENT).isEqualByComparingTo("0.03")
        assertThat(Constants.FIVE_PERCENT).isEqualByComparingTo("0.05")
        assertThat(Constants.TEN_PERCENT).isEqualByComparingTo("0.10")
        assertThat(Constants.QUARTER_PERCENT).isEqualByComparingTo("0.0025")
        assertThat(Constants.BPS).isEqualByComparingTo("0.0001")
    }

    @Test
    fun `lookup by name returns the same value`() {
        assertThat(Constants.byName("ONE_PERCENT")).isEqualByComparingTo("0.01")
        assertThat(Constants.byName("UNKNOWN")).isNull()
    }
}
