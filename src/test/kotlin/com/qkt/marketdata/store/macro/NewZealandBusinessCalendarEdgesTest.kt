package com.qkt.marketdata.store.macro

import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NewZealandBusinessCalendarEdgesTest {
    @Test
    fun `a Christmas on Sunday moves its holiday to Tuesday after Boxing Day`() {
        // 2022-12-25 was a Sunday: Monday is Boxing Day, Tuesday the observed Christmas.
        assertThat(NewZealandBusinessCalendar.nextBusinessDay(LocalDate.of(2022, 12, 23)))
            .isEqualTo(LocalDate.of(2022, 12, 28))
    }

    @Test
    fun `a release that would fall after the audited years is refused`() {
        assertThatThrownBy { NewZealandBusinessCalendar.nextBusinessDay(LocalDate.of(2052, 12, 31)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("outside the audited calendar: 2053-01-01")
    }
}
