package com.qkt.cli.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsonStringLiteralTest {
    @Test
    fun `plain text is only quoted`() {
        assertThat(jsonString("XAUUSD 1m")).isEqualTo("\"XAUUSD 1m\"")
        assertThat(jsonString("")).isEqualTo("\"\"")
    }

    @Test
    fun `quotes, backslashes and control characters are escaped`() {
        assertThat(jsonString("a\"b\\c\nd\re\tf")).isEqualTo("\"a\\\"b\\\\c\\nd\\re\\tf\"")
    }

    @Test
    fun `an escaped literal reads back as the original text`() {
        val original = "C:\\data\\\"gold\"\n\tline two\r"

        assertThat(Json.parseToJsonElement(jsonString(original)).jsonPrimitive.content).isEqualTo(original)
    }
}
