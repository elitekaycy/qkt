package com.qkt.lsp

import com.qkt.cli.VocabularyJson
import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.TokenKind
import com.qkt.dsl.stdlib.Constants
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Guards against the editor surface drifting from qkt's real front-end: every registry name has
 * hover docs and completion, every documented name exists, every documented indicator signature
 * compiles, every keyword has hover text, the vocabulary JSON lists every table, and member
 * completion offers exactly what the parser accepts after each pseudo-symbol.
 */
class DocsDriftTest {
    @Test
    fun `every indicator has hover docs and appears in completion`() {
        for (name in IndicatorRegistry.names() + DslVocabulary.externalIndicators) {
            assertThat(QktDocs.indicator(name)).withFailMessage("missing hover doc for indicator %s", name).isNotBlank()
            assertThat(QktVocabulary.indicators)
                .withFailMessage("indicator %s missing from completion", name)
                .contains(name.lowercase())
        }
    }

    @Test
    fun `every function has hover docs and appears in completion`() {
        for (name in FuncRegistry.names() + DslVocabulary.clockPredicates) {
            assertThat(QktDocs.function(name)).withFailMessage("missing hover doc for function %s", name).isNotBlank()
            assertThat(QktVocabulary.functions)
                .withFailMessage("function %s missing from completion", name)
                .contains(name.lowercase())
        }
    }

    @Test
    fun `every constant resolves to a value and appears in completion`() {
        for (name in Constants.names()) {
            assertThat(Constants.byName(name)).withFailMessage("constant %s has no value", name).isNotNull()
            assertThat(
                QktVocabulary.constants,
            ).withFailMessage("constant %s missing from completion", name).contains(name)
        }
    }

    @Test
    fun `every lexer keyword has hover text`() {
        for (keyword in DslVocabulary.keywords) {
            assertThat(
                QktDocs.keyword(keyword),
            ).withFailMessage("missing hover doc for keyword %s", keyword).isNotBlank()
        }
    }

    @Test
    fun `every documented indicator and function is a name the compiler knows`() {
        assertThat(QktDocs.indicatorNames())
            .isSubsetOf(IndicatorRegistry.names() + DslVocabulary.externalIndicators)
        assertThat(QktDocs.functionNames()).isSubsetOf(FuncRegistry.names() + DslVocabulary.clockPredicates)
    }

    @Test
    fun `every indicator hover signature compiles exactly as documented`() {
        for (name in IndicatorRegistry.names() + DslVocabulary.externalIndicators) {
            val signature = QktDocs.signature(QktDocs.indicator(name) ?: "")
            assertThat(signature).withFailMessage("indicator %s has no signature line", name).isNotNull()
            val documentedArgs = IndicatorSignatureFixture.parameters(signature ?: "")
            val seriesCount = IndicatorRegistry.spec(name)?.seriesCount ?: (documentedArgs.size - 1)
            val source = IndicatorSignatureFixture.strategy(signature ?: "", seriesCount)
            val parsed = Dsl.parse(source)
            assertThat(parsed)
                .withFailMessage("signature of %s does not parse: %s\n%s", name, signature, parsed)
                .isInstanceOf(ParseResult.Success::class.java)
            assertThatCode { AstCompiler().compile((parsed as ParseResult.Success).value) }
                .withFailMessage("signature of %s does not compile: %s\n%s", name, signature, source)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `a name that is both keyword and function is offered once, as a function`() {
        assertThat(
            QktVocabulary.keywords,
        ).doesNotContain("LOG", "FLOOR", "MIN", "MAX", "SUM", "MEAN", TokenKind.ARROW.name)
        assertThat(QktVocabulary.functions).contains("log", "floor", "min", "max", "sum", "mean", "avg", "count")
        assertThat(QktVocabulary.functions).contains("calendar_window", "session_window", "last_trading_day_of_month")
        assertThat(QktVocabulary.indicators).contains("resid", "confirm_ratio")
        assertThat(QktVocabulary.keywords).doesNotHaveDuplicates()
    }

    @Test
    fun `vocabulary json lists every registry name and member table`() {
        val json = Json.parseToJsonElement(VocabularyJson.render()).jsonObject

        fun names(key: String) =
            json.getValue(key).jsonArray.map {
                it.jsonObject
                    .getValue("name")
                    .jsonPrimitive.content
            }

        fun strings(key: String) = json.getValue(key).jsonArray.map { it.jsonPrimitive.content }

        assertThat(json.getValue("schema").jsonPrimitive.content).isEqualTo(VocabularyJson.SCHEMA)
        assertThat(strings("keywords")).containsExactlyElementsOf(DslVocabulary.keywords).doesNotContain("ARROW")
        assertThat(names("indicators"))
            .containsAll((IndicatorRegistry.names() + DslVocabulary.externalIndicators).map { it.lowercase() })
        assertThat(names("functions")).containsAll(
            (FuncRegistry.names() + DslVocabulary.clockPredicates).map {
                it.lowercase()
            },
        )
        assertThat(names("constants")).containsExactlyInAnyOrderElementsOf(Constants.names())
        assertThat(strings("streamFields")).containsExactlyInAnyOrderElementsOf(DslVocabulary.candleFields)
        assertThat(strings("metaFields")).containsExactlyInAnyOrderElementsOf(DslVocabulary.metaFields)
        assertThat(strings("seriesSelectors")).containsExactlyInAnyOrderElementsOf(DslVocabulary.seriesSelectors)
        assertThat(strings("shorthands")).containsExactlyInAnyOrder("avg", "count", "mean", "sum")
        val members = json.getValue("members").jsonObject
        assertThat(members.keys).containsExactlyInAnyOrderElementsOf(DslVocabulary.members.keys)
        for ((owner, table) in DslVocabulary.members) {
            assertThat(members.getValue(owner).jsonArray.map { it.jsonPrimitive.content })
                .withFailMessage("member table for %s drifted", owner)
                .containsExactlyInAnyOrderElementsOf(table)
        }
    }

    @Test
    fun `completion after each pseudo-symbol owner offers exactly its member table`() {
        val doc =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            SEQUENCE setup ON btc {
                STAGE dip: btc.close < 1
                STAGE pop: btc.close > 1
            }
            RULES
                WHEN btc.close > 0 THEN BUY btc SIZING 1
            """.trimIndent()
        val ast = DiagnosticsRunner.analyze(doc).parsed
        assertThat(ast).isNotNull()

        fun labelsAfter(prefix: String) = CompletionProvider.complete(prefix, 0, prefix.length, ast).map { it.label }

        for (owner in listOf("NOW", "ACCOUNT", "EXIT", "STREAK", "TRADES", "COOLDOWN")) {
            assertThat(labelsAfter("WHEN $owner."))
                .withFailMessage("completion after %s. drifted from its member table", owner)
                .containsExactlyElementsOf(DslVocabulary.members.getValue(owner))
        }
        assertThat(
            labelsAfter("WHEN POSITION.btc."),
        ).containsExactlyElementsOf(DslVocabulary.members.getValue("POSITION"))
        assertThat(labelsAfter("WHEN SEQUENCE.setup.")).containsExactly("stage", "complete", "dip", "pop")
        assertThat(labelsAfter("WHEN SEQUENCE.setup.dip."))
            .containsExactlyElementsOf(DslVocabulary.members.getValue(DslVocabulary.SEQUENCE_STAGE_OWNER))
    }
}
