package com.qkt.cli.observe

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatusSnapshotSerializersTest {
    @Test
    fun `a decimal is a raw JSON number and reads back as the same decimal`() {
        assertThat(Json.encodeToString(BigDecimalAsNumberSerializer, BigDecimal("1.50"))).isEqualTo("1.50")
        assertThat(Json.decodeFromString(BigDecimalAsNumberSerializer, "2.5")).isEqualByComparingTo("2.5")
    }

    @Test
    fun `a stream stop round trips through json`() {
        val stop = StreamStopDto("brain", "EXNESS:XAUUSD", "roll failed")

        val json = Json.encodeToString(StreamStopDto.serializer(), stop)

        assertThat(json).isEqualTo("""{"strategyId":"brain","stream":"EXNESS:XAUUSD","reason":"roll failed"}""")
        assertThat(Json.decodeFromString(StreamStopDto.serializer(), json)).isEqualTo(stop)
    }
}
