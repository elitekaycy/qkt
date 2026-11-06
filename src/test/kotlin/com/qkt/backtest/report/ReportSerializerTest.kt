package com.qkt.backtest.report

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportSerializerTest {
    @Test
    fun `json strings escape quotes, backslashes, whitespace and control characters`() {
        val raw = "a\"b\\c\nd\re\tf\u0001g"

        assertThat(ReportSerializer.jsonString(raw)).isEqualTo("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\"")
    }

    @Test
    fun `nullable decimals render null or a quoted plain string`() {
        assertThat(ReportSerializer.jsonNullableBigDecimal(null)).isEqualTo("null")
        assertThat(ReportSerializer.jsonNullableBigDecimal(BigDecimal("1E+3"))).isEqualTo("\"1000\"")
    }
}
