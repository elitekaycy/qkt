package com.qkt.dsl.portfolio

import com.qkt.dsl.ast.ParamDecl
import java.nio.file.Path
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PortfolioOverrideDeterminismTest {
    private val childDefault =
        """
        STRATEGY child VERSION 1
        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m
        PARAM riskPct = 0.01
        RULES
            WHEN btc.close > 100
            THEN BUY btc SIZING riskPct
        """.trimIndent()

    private val childHandEdited =
        """
        STRATEGY child VERSION 1
        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m
        PARAM riskPct = 0.008
        RULES
            WHEN btc.close > 100
            THEN BUY btc SIZING riskPct
        """.trimIndent()

    private fun params(c: CompiledChild): List<ParamDecl> = c.ast.params

    @Test
    fun `override equals hand-edit`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("child.qkt").writeText(childDefault)
        tmp.resolve("hand.qkt").writeText(childHandEdited)

        tmp.resolve("book.qkt").writeText(
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS a
            RULES
                RUN a OVERRIDE { riskPct = 0.008 }
            """.trimIndent(),
        )
        tmp.resolve("hand-book.qkt").writeText(
            """
            PORTFOLIO handbook VERSION 1
            IMPORT 'hand.qkt' AS a
            RULES
                RUN a
            """.trimIndent(),
        )

        val overrideParams = params(PortfolioLoader.load(tmp.resolve("book.qkt")).children.single())
        val handEditParams = params(PortfolioLoader.load(tmp.resolve("hand-book.qkt")).children.single())

        assertThat(overrideParams).isEqualTo(handEditParams)
    }

    @Test
    fun `loading the same portfolio twice yields identical params`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("child.qkt").writeText(childDefault)
        tmp.resolve("book.qkt").writeText(
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS a
            RULES
                RUN a OVERRIDE { riskPct = 0.008 }
            """.trimIndent(),
        )

        val first = params(PortfolioLoader.load(tmp.resolve("book.qkt")).children.single())
        val second = params(PortfolioLoader.load(tmp.resolve("book.qkt")).children.single())

        assertThat(first).isEqualTo(second)
    }
}
