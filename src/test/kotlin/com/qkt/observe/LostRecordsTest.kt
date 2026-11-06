package com.qkt.observe

import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LostRecordsTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val tuesday = LocalDate.parse("2026-11-03")

    @Test
    fun `two losses add their counts and merge their days keeping the latest error`() {
        val first = LostRecords(3, 2, mapOf(monday to 3L), "disk full")
        val second = LostRecords(4, 1, mapOf(monday to 1L, tuesday to 3L), "io error")

        val sum = first + second

        assertThat(sum).isEqualTo(LostRecords(7, 3, mapOf(monday to 4L, tuesday to 3L), "io error"))
        assertThat(sum.decisions).isEqualTo(4)
    }

    @Test
    fun `adding nothing lost keeps the other side`() {
        val loss = LostRecords(1, 0, mapOf(monday to 1L), "x")

        assertThat(loss + LostRecords.NONE).isSameAs(loss)
        assertThat(LostRecords.NONE + loss).isSameAs(loss)
    }
}
