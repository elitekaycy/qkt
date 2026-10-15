package com.qkt.marketdata.ingest.parse

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DecimalTextTest {
    @Test
    fun `keeps a vendor's float noise exactly instead of rounding it through a double`() {
        assertThat(DecimalText.parse("981.6912000000001")).isEqualTo(BigDecimal("981.6912000000001"))
    }

    @Test
    fun `reads scientific notation exactly`() {
        assertThat(DecimalText.parse("3.146e-05")!!.compareTo(BigDecimal("0.00003146"))).isZero()
        assertThat(DecimalText.parse("9.816912000000002e+10")!!.toPlainString()).isEqualTo("98169120000.00002")
    }

    @Test
    fun `reads a decimal comma when the source uses one`() {
        assertThat(DecimalText.parse("1,10024", NumberStyle(decimal = ','))).isEqualTo(BigDecimal("1.10024"))
    }

    @Test
    fun `refuses a dot in a decimal-comma column`() {
        assertThatThrownBy {
            DecimalText.parse("1.234,5", NumberStyle(decimal = ','))
        }.isInstanceOf(NumberFormatException::class.java)
    }

    @Test
    fun `refuses thousands separators rather than guessing what they mean`() {
        assertThatThrownBy { DecimalText.parse("1,234.56") }.isInstanceOf(NumberFormatException::class.java)
    }

    @Test
    fun `null tokens are absent, never zero`() {
        listOf(
            "",
            "NaN",
            "null",
            "-",
            "N/A",
            "  ",
        ).forEach { assertThat(DecimalText.parse(it)).describedAs(it).isNull() }
    }

    @Test
    fun `refuses text`() {
        assertThat(DecimalText.isNumber("buy")).isFalse()
        assertThat(DecimalText.isNumber("-0.5")).isTrue()
    }
}
