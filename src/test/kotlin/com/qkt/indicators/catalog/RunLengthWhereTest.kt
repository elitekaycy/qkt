package com.qkt.indicators.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunLengthWhereTest {
    @Test
    fun `null before the first condition sample`() {
        val dwell = RunLengthWhere()

        assertThat(dwell.isReady).isFalse()
        assertThat(dwell.value()).isNull()
        assertThat(dwell.warmupBars).isEqualTo(1)
    }

    @Test
    fun `counts consecutive true samples and resets on false`() {
        val dwell = RunLengthWhere()

        dwell.update(true)
        assertThat(dwell.isReady).isTrue()
        assertThat(dwell.value()).isEqualByComparingTo("1")

        dwell.update(true)
        assertThat(dwell.value()).isEqualByComparingTo("2")

        dwell.update(false)
        assertThat(dwell.value()).isEqualByComparingTo("0")

        dwell.update(true)
        assertThat(dwell.value()).isEqualByComparingTo("1")
    }
}
