package com.qkt.cli

import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Test
    fun `parses every allocation method instead of silently falling back to fixed`() {
        for (method in com.qkt.risk.book.AllocationMethod.entries) {
            val f = Files.createTempFile("qkt-$method", ".yaml")
            Files.writeString(
                f,
                """
                book_risk:
                  allocation:
                    method: $method
                """.trimIndent(),
            )

            val bookRisk = requireNotNull(Config.load(f).bookRisk)
            val allocation = requireNotNull(bookRisk.allocation)
            assertThat(allocation.method).isEqualTo(method)
        }
    }

    @Test
    fun `rejects an unknown allocation method instead of changing it to fixed`() {
        val f = Files.createTempFile("qkt-unknown-allocation", ".yaml")
        Files.writeString(
            f,
            """
            book_risk:
              allocation:
                method: INVERSE_VOLUME
            """.trimIndent(),
        )

        assertThatThrownBy { Config.load(f) }
            .hasMessageContaining("unknown book_risk.allocation.method 'INVERSE_VOLUME'")
    }
}
