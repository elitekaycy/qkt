package com.qkt.instrument

import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RollPolicyTest {
    private val expiry = Instant.parse("2024-09-27T08:00:00Z").toEpochMilli()

    @Test
    fun `the roll is a calendar offset from the expiry date at a fixed UTC time`() {
        val policy = RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA)
        assertThat(policy.rollAtMs(expiry)).isEqualTo(Instant.parse("2024-09-19T08:00:00Z").toEpochMilli())
    }

    @Test
    fun `a roll at or after expiry is refused`() {
        assertThatThrownBy { RollPolicy(0, LocalTime.of(9, 0), PriceAdjustment.NONE).rollAtMs(expiry) }
            .hasMessageContaining("before expiry")
    }

    @Test
    fun `negative day offsets are refused`() {
        assertThatThrownBy {
            RollPolicy(
                -1,
                LocalTime.NOON,
                PriceAdjustment.NONE,
            )
        }.hasMessageContaining("daysBeforeExpiry")
    }
}
