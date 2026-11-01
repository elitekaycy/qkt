package com.qkt.lsp

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentStoreTest {
    private val validSource =
        """
        STRATEGY example VERSION 1

        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m

        RULES
            WHEN btc.close > 100
            THEN BUY btc SIZING 1
        """.trimIndent()

    @Test
    fun `stores text and parsed ast`() {
        val store = DocumentStore()
        val ast = (Dsl.parseAny(validSource) as ParseResult.Success).value
        store.put("file:///a.qkt", validSource, ast)
        assertThat(store.text("file:///a.qkt")).isEqualTo(validSource)
        assertThat(store.lastGoodAst("file:///a.qkt")).isSameAs(ast)
    }

    @Test
    fun `keeps last good ast when a later edit fails to parse`() {
        val store = DocumentStore()
        val ast = (Dsl.parseAny(validSource) as ParseResult.Success).value
        store.put("file:///a.qkt", validSource, ast)
        store.put("file:///a.qkt", "STRATEGY broken VERSION", null)
        assertThat(store.text("file:///a.qkt")).isEqualTo("STRATEGY broken VERSION")
        assertThat(store.lastGoodAst("file:///a.qkt")).isSameAs(ast)
    }

    @Test
    fun `remove clears the entry`() {
        val store = DocumentStore()
        store.put("file:///a.qkt", "x", null)
        store.remove("file:///a.qkt")
        assertThat(store.text("file:///a.qkt")).isNull()
    }

    @Test
    fun `an analysis of text the user has since edited is discarded`() {
        val store = DocumentStore()
        val ast = (Dsl.parseAny(validSource) as ParseResult.Success).value
        store.put("file:///a.qkt", validSource, null)
        store.put("file:///a.qkt", "$validSource\n-- newer", null)
        assertThat(store.putAnalysis("file:///a.qkt", validSource, ast)).isFalse()
        assertThat(store.text("file:///a.qkt")).endsWith("-- newer")
        assertThat(store.lastGoodAst("file:///a.qkt")).isNull()
    }

    @Test
    fun `an analysis of the current text records its ast`() {
        val store = DocumentStore()
        val ast = (Dsl.parseAny(validSource) as ParseResult.Success).value
        store.put("file:///a.qkt", validSource, null)
        assertThat(store.putAnalysis("file:///a.qkt", validSource, ast)).isTrue()
        assertThat(store.lastGoodAst("file:///a.qkt")).isSameAs(ast)
    }
}
