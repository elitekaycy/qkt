package com.qkt.dsl.compile

import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TifTranslatorTest {
    @Test
    fun `each TIF maps to its engine enum`() {
        assertThat(TifTranslator.translate(Gtc)).isEqualTo(TimeInForce.GTC)
        assertThat(TifTranslator.translate(Ioc)).isEqualTo(TimeInForce.IOC)
        assertThat(TifTranslator.translate(Fok)).isEqualTo(TimeInForce.FOK)
        assertThat(TifTranslator.translate(Day)).isEqualTo(TimeInForce.DAY)
    }

    @Test
    fun `default TIF is GTC`() {
        assertThat(TifTranslator.translate(null)).isEqualTo(TimeInForce.GTC)
    }

    @Test
    fun `GTD maps to TimeInForce GTD`() {
        assertThat(TifTranslator.translate(Gtd(NumLit(BigDecimal("1700000000000"))))).isEqualTo(TimeInForce.GTD)
    }
}
