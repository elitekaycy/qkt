package com.qkt.dsl.compile

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AstCompilerMetaValidationTest {
    private val xauMeta =
        InstrumentMeta(
            qktSymbol = "EXNESS:XAUUSD",
            contractSize = BigDecimal("100"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
        )

    private fun registry(map: Map<String, InstrumentMeta>): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = map[qktSymbol]
        }

    private fun parseStrategy(text: String): com.qkt.dsl.ast.StrategyAst {
        val parsed = Parser(Lexer(text).tokenize()).parseFile()
        require(parsed is ParseResult.Success) { "parse failed: $parsed" }
        return (parsed.value as ParsedFile.StrategyFile).ast
    }

    private fun bind(
        strategy: DslCompiledStrategy,
        reg: InstrumentRegistry,
    ) {
        val hub = CandleHub()
        strategy.declaredStreams.values.forEach { hub.register(it, retention = 1, strategyId = "test") }
        val ctx = testStrategyContext(instruments = reg)
        strategy.bindToHub(hub, ctx) { _: Signal -> }
    }

    @Test
    fun `strategy with meta refs binds when registry has the instrument`() {
        val ast =
            parseStrategy(
                """
                STRATEGY meta_ok VERSION 1
                SYMBOLS
                    gold = EXNESS:XAUUSD EVERY 1m
                RULES
                    WHEN gold.close > gold.tick_size * 100
                    THEN BUY gold SIZING gold.volume_min
                """.trimIndent(),
            )
        val strategy = AstCompiler().compile(ast) as DslCompiledStrategy
        assertThatCode { bind(strategy, registry(mapOf("EXNESS:XAUUSD" to xauMeta))) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `strategy with meta refs fails to bind when registry is missing the instrument`() {
        val ast =
            parseStrategy(
                """
                STRATEGY meta_missing VERSION 1
                SYMBOLS
                    gold = EXNESS:XAUUSD EVERY 1m
                RULES
                    WHEN gold.close > gold.tick_size
                    THEN BUY gold SIZING 0.01
                """.trimIndent(),
            )
        val strategy = AstCompiler().compile(ast) as DslCompiledStrategy
        assertThatThrownBy { bind(strategy, NoopInstrumentRegistry) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'gold.tick_size'")
            .hasMessageContaining("EXNESS:XAUUSD")
    }

    @Test
    fun `strategy without meta refs binds against an empty registry`() {
        val ast =
            parseStrategy(
                """
                STRATEGY no_meta VERSION 1
                SYMBOLS
                    gold = EXNESS:XAUUSD EVERY 1m
                RULES
                    WHEN gold.close > 0
                    THEN BUY gold SIZING 0.01
                """.trimIndent(),
            )
        val strategy = AstCompiler().compile(ast) as DslCompiledStrategy
        assertThatCode { bind(strategy, NoopInstrumentRegistry) }
            .doesNotThrowAnyException()
    }
}
