package com.qkt.marketdata.ingest.parse

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TimeFormatGuessTest {
    private val now = 1_792_022_400_000L

    private fun guess(vararg samples: String) = TimeFormatGuess.resolve(null, samples.toList(), now)

    @Test
    fun `numbers become an epoch with the unit their magnitude says`() {
        assertThat(guess("1595311200", "1595314800")).isEqualTo(TimeFormat.Epoch(EpochUnit.SECONDS))
    }

    @Test
    fun `an eight-digit number column is a date, not an epoch`() {
        assertThat(guess("20240101", "20240102")).isEqualTo(TimeFormat.Pattern("yyyyMMdd"))
    }

    @Test
    fun `a day above twelve settles day-first`() {
        assertThat(guess("03/04/2024 10:00", "21/04/2024 10:00")).isEqualTo(TimeFormat.Pattern("dd/MM/yyyy HH:mm"))
    }

    @Test
    fun `day-first or month-first that nothing settles refuses`() {
        assertThatThrownBy { guess("03/04/2024 10:00", "05/04/2024 10:00") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("fits several formats")
    }

    @Test
    fun `an epoch outside 1990 to now refuses, so a wrong unit cannot pass`() {
        assertThatThrownBy {
            TimeFormatGuess.resolve(
                TimeFormat.Epoch(EpochUnit.MILLIS),
                listOf("1748736000000000"),
                now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("outside 1990..now")
    }

    @Test
    fun `a stated pattern is checked against every sample`() {
        assertThatThrownBy {
            TimeFormatGuess.resolve(
                TimeFormat.Pattern("yyyy.MM.dd HH:mm"),
                listOf("2024-01-02 10:00"),
                now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `text that fits no format refuses with the flag to pass`() {
        assertThatThrownBy { guess("yesterday") }.hasMessageContaining("--time-format")
    }
}
