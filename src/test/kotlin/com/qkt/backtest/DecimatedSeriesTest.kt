package com.qkt.backtest

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DecimatedSeriesTest {
    @Test
    fun `a cap under two is refused`() {
        assertThatThrownBy { DecimatedSeries<Int>(cap = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cap must be >= 2")
    }

    @Test
    fun `an empty series has an empty snapshot`() {
        assertThat(DecimatedSeries<Int>(cap = 4).snapshot()).isEmpty()
    }

    @Test
    fun `over the cap every other kept sample is dropped and the stride doubles`() {
        val series = DecimatedSeries<Int>(cap = 4)

        (1..9).forEach(series::accept)

        assertThat(series.snapshot()).containsExactly(1, 5, 9)
    }

    @Test
    fun `the snapshot appends the latest sample when the stride skipped it`() {
        val series = DecimatedSeries<Int>(cap = 4)

        (1..12).forEach(series::accept)

        assertThat(series.snapshot()).containsExactly(1, 5, 9, 12)
    }
}
