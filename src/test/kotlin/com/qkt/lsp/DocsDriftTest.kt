package com.qkt.lsp

import com.qkt.dsl.stdlib.Constants
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards against the editor vocabulary drifting from qkt's real registries: every indicator
 * and function the engine knows must have hover documentation and must be offered in
 * completion. Adding a new indicator to qkt fails these tests until its docs are written.
 */
class DocsDriftTest {
    @Test
    fun `every indicator has hover docs and appears in completion`() {
        for (name in IndicatorRegistry.names()) {
            assertThat(QktDocs.indicator(name))
                .withFailMessage("missing hover doc for indicator %s", name)
                .isNotBlank()
            assertThat(QktVocabulary.indicators)
                .withFailMessage("indicator %s missing from completion", name)
                .contains(name.lowercase())
        }
    }

    @Test
    fun `every function has hover docs and appears in completion`() {
        for (name in FuncRegistry.names()) {
            assertThat(QktDocs.function(name))
                .withFailMessage("missing hover doc for function %s", name)
                .isNotBlank()
            assertThat(QktVocabulary.functions)
                .withFailMessage("function %s missing from completion", name)
                .contains(name.lowercase())
        }
    }

    @Test
    fun `every constant resolves to a value and appears in completion`() {
        for (name in Constants.names()) {
            assertThat(Constants.byName(name))
                .withFailMessage("constant %s has no value", name)
                .isNotNull()
            assertThat(QktVocabulary.constants)
                .withFailMessage("constant %s missing from completion", name)
                .contains(name)
        }
    }
}
