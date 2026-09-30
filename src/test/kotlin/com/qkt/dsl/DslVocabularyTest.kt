package com.qkt.dsl

import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * The member tables are what the parser and compiler accept: every listed spelling parses and
 * compiles in the position its owner is read from, and a spelling outside the table is rejected.
 */
class DslVocabularyTest {
    private fun strategy(
        condition: String,
        declarations: String = "",
        action: String = "FLATTEN",
    ): String =
        """
        STRATEGY t VERSION 1
        SYMBOLS
            g = X:Y EVERY 1m
        $declarations
        RULES
            WHEN $condition THEN $action
        """.trimIndent()

    private fun compiles(source: String) {
        val parsed = Dsl.parse(source)
        assertThat(
            parsed,
        ).withFailMessage("does not parse:\n%s\n%s", source, parsed).isInstanceOf(ParseResult.Success::class.java)
        assertThatCode { AstCompiler().compile((parsed as ParseResult.Success).value) }
            .withFailMessage("does not compile:\n%s", source)
            .doesNotThrowAnyException()
    }

    @Test
    fun `every POSITION member compiles after a stream alias`() {
        for (member in DslVocabulary.members.getValue("POSITION")) compiles(strategy("POSITION.g.$member > 0"))
    }

    @Test
    fun `every NOW ACCOUNT STREAK TRADES and COOLDOWN member compiles`() {
        for (owner in listOf("NOW", "ACCOUNT", "STREAK", "TRADES", "COOLDOWN")) {
            for (member in DslVocabulary.members.getValue(owner)) compiles(strategy("$owner.$member > 0"))
        }
    }

    @Test
    fun `every EXIT member compiles inside an exit hook`() {
        for (member in DslVocabulary.members.getValue("EXIT")) {
            compiles(
                strategy(
                    "g.close > 0",
                    action = "BUY g SIZING 1 ON_CLOSE { BUY g SIZING 1; LOG \"e {v}\" v = EXIT.$member }",
                ),
            )
        }
    }

    @Test
    fun `every SEQUENCE and stage member compiles against a declared sequence`() {
        val sequence = "SEQUENCE setup ON g {\n    STAGE dip: g.close < 1\n    STAGE pop: g.close > 1\n}"
        for (member in DslVocabulary.sequenceMembers) compiles(strategy("SEQUENCE.setup.$member > 0", sequence))
        for (member in DslVocabulary.sequenceStageMembers) {
            compiles(
                strategy("SEQUENCE.setup.dip.$member > 0", sequence),
            )
        }
    }

    @Test
    fun `every stream field and both series selectors compile`() {
        for (field in DslVocabulary.candleFields + DslVocabulary.metaFields) compiles(strategy("g.$field > 0"))
        compiles(strategy("atr(g.${DslVocabulary.CANDLE_SELECTOR}, 14) > 0"))
        compiles(strategy("vwap(g.${DslVocabulary.TICK_SELECTOR}, 20) > 0"))
    }

    @Test
    fun `a spelling outside a member table is rejected where it used to be`() {
        assertThat(Dsl.parse(strategy("POSITION.g.bogus > 0"))).isInstanceOf(ParseResult.Failure::class.java)
        assertThat(Dsl.parse(strategy("NOW.bogus > 0"))).isInstanceOf(ParseResult.Failure::class.java)
        val account = Dsl.parse(strategy("ACCOUNT.bogus > 0")) as ParseResult.Success
        assertThatCode { AstCompiler().compile(account.value) }.hasMessageContaining("Unsupported ACCOUNT field: bogus")
    }

    @Test
    fun `keywords exclude the arrow token and cover every lexer spelling once`() {
        assertThat(
            DslVocabulary.keywords,
        ).doesNotContain("ARROW").contains("WHEN", "POSITION", "LOG").doesNotHaveDuplicates()
        assertThat(DslVocabulary.members.values.flatten()).allMatch { it == it.lowercase() }
    }
}
