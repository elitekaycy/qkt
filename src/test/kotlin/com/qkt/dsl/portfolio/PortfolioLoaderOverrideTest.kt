package com.qkt.dsl.portfolio

import com.qkt.dsl.ast.NumLit
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PortfolioLoaderOverrideTest {
    private val child =
        """
        STRATEGY child VERSION 1
        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m
        PARAM riskPct = 0.01
        RULES
            WHEN btc.close > 100
            THEN BUY btc SIZING riskPct
        """.trimIndent()

    private fun riskPctOf(c: CompiledChild): BigDecimal =
        (
            c.ast.params
                .first {
                    it.name == "riskPct"
                }.value as NumLit
        ).value

    @Test
    fun `the same child under two aliases gets different param values`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("child.qkt").writeText(child)
        val pf = tmp.resolve("portfolio.qkt")
        pf.writeText(
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS aggressive
            IMPORT 'child.qkt' AS conservative
            RULES
                RUN aggressive OVERRIDE { riskPct = 0.008 }
                RUN conservative OVERRIDE { riskPct = 0.003 }
            """.trimIndent(),
        )

        val compiled = PortfolioLoader.load(pf)
        val aggressive = compiled.children.first { it.alias == "aggressive" }
        val conservative = compiled.children.first { it.alias == "conservative" }
        assertThat(riskPctOf(aggressive)).isEqualByComparingTo(BigDecimal("0.008"))
        assertThat(riskPctOf(conservative)).isEqualByComparingTo(BigDecimal("0.003"))
    }

    @Test
    fun `unknown override key is an error`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("child.qkt").writeText(child)
        val pf = tmp.resolve("portfolio.qkt")
        pf.writeText(
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS a
            RULES
                RUN a OVERRIDE { nope = 1 }
            """.trimIndent(),
        )

        assertThatThrownBy { PortfolioLoader.load(pf) }
            .hasMessageContaining("nope")
    }

    @Test
    fun `type-mismatched override is an error`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("child.qkt").writeText(child)
        val pf = tmp.resolve("portfolio.qkt")
        pf.writeText(
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS a
            RULES
                RUN a OVERRIDE { riskPct = "hi" }
            """.trimIndent(),
        )

        assertThatThrownBy { PortfolioLoader.load(pf) }
            .hasMessageContaining("riskPct")
    }

    @Test
    fun `conflicting overrides for the same alias is an error`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("child.qkt").writeText(child)
        val pf = tmp.resolve("portfolio.qkt")
        pf.writeText(
            """
            PORTFOLIO book VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'child.qkt' AS a
            RULES
                WHEN btc.close > 100 RUN a OVERRIDE { riskPct = 0.008 }
                RUN a OVERRIDE { riskPct = 0.003 }
            """.trimIndent(),
        )

        assertThatThrownBy { PortfolioLoader.load(pf) }
            .hasMessageContaining("conflicting")
    }

    @Test
    fun `identical overrides for the same alias do not throw`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("child.qkt").writeText(child)
        val pf = tmp.resolve("portfolio.qkt")
        pf.writeText(
            """
            PORTFOLIO book VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'child.qkt' AS a
            RULES
                WHEN btc.close > 100 RUN a OVERRIDE { riskPct = 0.008 }
                RUN a OVERRIDE { riskPct = 0.008 }
            """.trimIndent(),
        )

        val compiled = PortfolioLoader.load(pf)
        assertThat(riskPctOf(compiled.children.first())).isEqualByComparingTo(BigDecimal("0.008"))
    }
}
