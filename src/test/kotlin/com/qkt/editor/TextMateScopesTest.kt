package com.qkt.editor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TextMateScopesTest {
    private val probe = TextMateProbe(GrammarGenerator.textMate())

    private fun scopes(line: String): List<String> = probe.tokens(line).map { "${it.text}=${it.scope}" }

    @Test
    fun `a SYMBOLS line scopes the alias, the broker prefix, the cadence and the duration`() {
        assertThat(scopes("  gold = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS,"))
            .containsExactly(
                "gold=variable.other.alias.qkt",
                "==keyword.operator.qkt",
                "EXNESS:=entity.name.namespace.qkt",
                "EVERY=keyword.control.flow.qkt",
                "5m=constant.numeric.duration.qkt",
                "WARMUP=keyword.other.session.qkt",
                "50=constant.numeric.qkt",
                "BARS=keyword.other.session.qkt",
            )
    }

    @Test
    fun `a rule line scopes indicators, fields, aliases, word operators and the buy target`() {
        assertThat(scopes("WHEN ema(gold.close, 20) CROSSES ABOVE slow AND NOT flat THEN BUY gold SIZING 1"))
            .containsExactly(
                "WHEN=keyword.control.flow.qkt",
                "ema=support.function.indicator.qkt",
                "gold=variable.other.alias.qkt",
                "close=support.variable.field.qkt",
                "20=constant.numeric.qkt",
                "CROSSES=keyword.operator.word.qkt",
                "ABOVE=keyword.operator.word.qkt",
                "AND=keyword.operator.word.qkt",
                "NOT=keyword.operator.word.qkt",
                "THEN=keyword.control.flow.qkt",
                "BUY=keyword.other.action.qkt",
                "gold=variable.other.alias.qkt",
                "SIZING=keyword.other.sizing.qkt",
                "1=constant.numeric.qkt",
            )
    }

    @Test
    fun `pseudo-symbol reads scope the symbol, the alias member and the field`() {
        assertThat(scopes("position.gold.qty <> 0 OR NOW.hour_utc = 9 OR SEQUENCE.setup.stage"))
            .containsExactly(
                "position=variable.language.qkt",
                "gold=variable.other.member.qkt",
                "qty=support.variable.field.qkt",
                "<>=keyword.operator.qkt",
                "0=constant.numeric.qkt",
                "OR=keyword.operator.word.qkt",
                "NOW=variable.language.qkt",
                "hour_utc=support.variable.field.qkt",
                "==keyword.operator.qkt",
                "9=constant.numeric.qkt",
                "OR=keyword.operator.word.qkt",
                "SEQUENCE=keyword.control.section.qkt",
                "setup=variable.other.member.qkt",
                "stage=support.variable.field.qkt",
            )
    }

    @Test
    fun `keywords that are also functions keep their keyword scope, other calls are builtins`() {
        assertThat(scopes("LOG(x) + abs(y) - FLOOR(z)"))
            .containsExactly(
                "LOG=keyword.other.action.qkt",
                "+=keyword.operator.qkt",
                "abs=support.function.builtin.qkt",
                "-=keyword.operator.qkt",
                "FLOOR=keyword.other.order.qkt",
            )
        assertThat(
            scopes("atr > ONE_PERCENT"),
        ).containsExactly(">=keyword.operator.qkt", "ONE_PERCENT=constant.language.qkt")
    }

    @Test
    fun `strings are single-line with only the lexer's five escapes and comments win over operators`() {
        assertThat(scopes("""LOG "a\n\q" -- note -> x"""))
            .containsExactly(
                "LOG=keyword.other.action.qkt",
                "\"a\\n\\q\"=string.quoted.double.qkt",
                "-- note -> x=comment.line.double-dash.qkt",
            )
        assertThat(probe.matches("constant.character.escape.qkt", "\\n")).isTrue()
        assertThat(probe.matches("constant.character.escape.qkt", "\\q")).isFalse()
        assertThat(probe.matches("invalid.illegal.escape.qkt", "\\q")).isTrue()
        assertThat(scopes("# hash /* block */")).containsExactly("# hash /* block */=comment.line.number-sign.qkt")
        assertThat(scopes("/* block */ x")).first().isEqualTo("/* block */=comment.block.qkt")
    }
}
