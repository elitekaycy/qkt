package com.qkt.observe.insights

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsEnvelopeJsonTest {
    @Test
    fun `values render by type with null map entries left out`() {
        val json =
            InsightsEnvelope.json(
                mapOf(
                    "s" to "x",
                    "b" to true,
                    "d" to BigDecimal("1E+3"),
                    "n" to 7,
                    "e" to Side.SELL,
                    "gone" to null,
                    "l" to listOf(1, null, "y"),
                    "o" to StringBuilder("raw"),
                ),
            )
        assertThat(
            json,
        ).isEqualTo("{\"s\":\"x\",\"b\":true,\"d\":1000,\"n\":7,\"e\":\"SELL\",\"l\":[1,null,\"y\"],\"o\":\"raw\"}")
        assertThat(InsightsEnvelope.json(null)).isEqualTo("null")
    }

    @Test
    fun `strings escape quotes backslashes and control characters`() {
        assertThat(InsightsEnvelope.json("a\"b\\c\nd\re\tf\u0001"))
            .isEqualTo("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001\"")
    }

    @Test
    fun `an envelope without a strategy omits the field`() {
        val json = InsightsEnvelope("i", 1L, 2L, null, "log", emptyMap()).toJson("inst")
        assertThat(
            json,
        ).isEqualTo("{\"v\":1,\"instanceId\":\"inst\",\"id\":\"i\",\"seq\":1,\"ts\":2,\"type\":\"log\",\"payload\":{}}")
    }
}
