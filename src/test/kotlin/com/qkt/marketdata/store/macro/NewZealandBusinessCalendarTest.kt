package com.qkt.marketdata.store.macro

import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class NewZealandBusinessCalendarTest {
    @Test
    fun `next business day skips a normal weekend`() {
        assertThat(NewZealandBusinessCalendar.nextBusinessDay(LocalDate.of(2024, 3, 8)))
            .isEqualTo(LocalDate.of(2024, 3, 11))
    }

    @Test
    fun `next business day skips Matariki`() {
        assertThat(NewZealandBusinessCalendar.nextBusinessDay(LocalDate.of(2026, 7, 9)))
            .isEqualTo(LocalDate.of(2026, 7, 13))
    }

    @Test
    fun `next business day skips Wellington anniversary`() {
        assertThat(NewZealandBusinessCalendar.nextBusinessDay(LocalDate.of(2026, 1, 16)))
            .isEqualTo(LocalDate.of(2026, 1, 20))
    }

    @Test
    fun `next business day applies paired Christmas transfer days`() {
        assertThat(NewZealandBusinessCalendar.nextBusinessDay(LocalDate.of(2021, 12, 24)))
            .isEqualTo(LocalDate.of(2021, 12, 29))
    }

    @Test
    fun `calendar fails outside its audited horizon`() {
        assertThatIllegalArgumentException()
            .isThrownBy { NewZealandBusinessCalendar.nextBusinessDay(LocalDate.of(2053, 1, 1)) }
    }
}
