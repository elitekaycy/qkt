package com.qkt.cli

import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigBookRiskTest {
    @Test
    fun `parses book_risk limits`() {
        val f = Files.createTempFile("qkt", ".yaml")
        Files.writeString(
            f,
            """
            book_risk:
              capital: "100000"
              limits:
                max_gross_exposure: "3.0"
                max_net_exposure: "1.5"
                max_symbol_concentration: "1.0"
            """.trimIndent(),
        )
        val br = Config.load(f).bookRisk!!
        assertThat(br.capital).isEqualByComparingTo("100000")
        assertThat(br.limits!!.maxGrossExposure).isEqualByComparingTo("3.0")
        assertThat(br.limits!!.maxNetExposure).isEqualByComparingTo("1.5")
        assertThat(br.limits!!.maxSymbolConcentration).isEqualByComparingTo("1.0")
    }

    @Test
    fun `absent book_risk is null`() {
        val f = Files.createTempFile("qkt", ".yaml")
        Files.writeString(f, "source: local\n")
        assertThat(Config.load(f).bookRisk).isNull()
    }
}
