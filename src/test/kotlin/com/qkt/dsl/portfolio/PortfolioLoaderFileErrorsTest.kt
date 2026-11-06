package com.qkt.dsl.portfolio

import com.qkt.dsl.ast.NumLit
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** [PortfolioLoader] refuses files that are not a loadable portfolio and keeps PARAMs no OVERRIDE names. */
class PortfolioLoaderFileErrorsTest {
    private val child =
        """
        STRATEGY child VERSION 1
        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m
        PARAM riskPct = 0.01
        PARAM fast = 9
        RULES
            WHEN btc.close > fast
            THEN BUY btc SIZING riskPct
        """.trimIndent()

    private fun portfolio(
        tmp: Path,
        body: String,
    ): Path = tmp.resolve("book.qkt").also { it.writeText("PORTFOLIO book VERSION 1\n$body\n") }

    @Test
    fun `a strategy file is not a portfolio`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("child.qkt").also { it.writeText(child) }

        assertThatThrownBy { PortfolioLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("expected PORTFOLIO at")
            .hasMessageContaining("got STRATEGY")
    }

    @Test
    fun `a portfolio that does not parse is reported with its path`(
        @TempDir tmp: Path,
    ) {
        val path = portfolio(tmp, "IMPORT AS nothing")

        assertThatThrownBy { PortfolioLoader.load(path) }
            .hasMessageContaining("parse error at $path")
    }

    @Test
    fun `a child that does not parse is reported with the child's path`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("broken.qkt").writeText("STRATEGY broken VERSION\n")
        val path = portfolio(tmp, "IMPORT 'broken.qkt' AS broken\nRULES\n    RUN broken")

        assertThatThrownBy { PortfolioLoader.load(path) }
            .hasMessageContaining("parse error at ${tmp.resolve("broken.qkt")}")
    }

    @Test
    fun `a portfolio importing itself is a cycle`(
        @TempDir tmp: Path,
    ) {
        val path = portfolio(tmp, "IMPORT 'book.qkt' AS me\nRULES\n    RUN me")

        assertThatThrownBy { PortfolioLoader.load(path) }
            .hasMessageContaining("cycle in portfolio import graph")
            .hasMessageContaining("imported by $path")
    }

    @Test
    fun `an OVERRIDE replaces only the PARAMs it names`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("child.qkt").writeText(child)
        val path = portfolio(tmp, "IMPORT 'child.qkt' AS c\nRULES\n    RUN c OVERRIDE { riskPct = 0.02 }")

        val params =
            PortfolioLoader.load(path).children.single().ast.params.associate {
                it.name to
                    (it.value as NumLit).value
            }

        assertThat(params["riskPct"]).isEqualByComparingTo(BigDecimal("0.02"))
        assertThat(params["fast"]).isEqualByComparingTo(BigDecimal("9"))
    }
}
