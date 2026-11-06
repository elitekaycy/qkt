package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A venue symbol may hold a decimal strike (`KXHIGHNY_26NOV06_B73.5`); nothing else about a symbol changes. */
class ParserDecimalStrikeSymbolTest {
    private fun parse(stream: String): ParseResult<StrategyAst> =
        Dsl.parse(
            """
            STRATEGY s VERSION 1
            SYMBOLS
                k = $stream EVERY 1m
            RULES
                WHEN k.close > 0 THEN LOG "x"
            """.trimIndent(),
        )

    private fun symbolOf(stream: String): String = (parse(stream) as ParseResult.Success).value.streams.single().symbol

    @Test
    fun `a decimal strike stays inside the symbol`() {
        val ast = (parse("KALSHI:KXHIGHNY_26NOV06_B73.5") as ParseResult.Success).value

        assertThat(ast.streams.single().symbol).isEqualTo("KXHIGHNY_26NOV06_B73.5")
        assertThat(ast.streams.single().qktSymbol).isEqualTo("KALSHI:KXHIGHNY_26NOV06_B73.5")
        assertThat(symbolOf("KALSHI:KXHIGHNY_26NOV06_B73.25")).isEqualTo("KXHIGHNY_26NOV06_B73.25")
        assertThat(symbolOf("KALSHI:T0.05")).isEqualTo("T0.05")
    }

    @Test
    fun `a symbol without a decimal parses as before`() {
        assertThat(symbolOf("KALSHI:KXINXU_26NOV06H1500_T7425")).isEqualTo("KXINXU_26NOV06H1500_T7425")
        assertThat(symbolOf("BINANCE_UM:BTCUSDT@front")).isEqualTo("BTCUSDT@front")
    }

    @Test
    fun `the dot joins only digits to digits with nothing between`() {
        assertThat(parse("KALSHI:B73 .5")).isInstanceOf(ParseResult.Failure::class.java)
        assertThat(parse("KALSHI:B73. 5")).isInstanceOf(ParseResult.Failure::class.java)
        assertThat(parse("KALSHI:ABC.5")).isInstanceOf(ParseResult.Failure::class.java)
        assertThat(parse("KALSHI:B73.5e2")).isInstanceOf(ParseResult.Failure::class.java)
        assertThat(parse("KALSHI:B73.x")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `existing corpus files keep their pinned fingerprints`() {
        val pins = Files.readAllLines(Path.of("src/test/resources/golden/dsl-corpus.txt"))
        val files =
            listOf(
                "examples/latch-stack/latch-stack.qkt",
                "attestation/cases/derivatives/continuous-roll-far-book-stops/strategy.qkt",
            )
        for (path in files) {
            val pinned = pins.single { it.endsWith("  $path") }.substringBefore("  ")
            val ast = (Dsl.parse(Files.readString(Path.of(path))) as ParseResult.Success).value

            assertThat(sha256(ast.toString())).describedAs(path).isEqualTo(pinned)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
