package com.qkt.dsl.sets

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SymbolSetParseTest {
    private fun parse(src: String): StrategyAst =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> r.value
            is ParseResult.Failure -> error("parse failed: ${r.errors}")
        }

    private fun errors(src: String): List<String> =
        (Dsl.parse(src) as ParseResult.Failure).errors.map {
            "${it.line}: ${it.message}"
        }

    private val picked =
        """
        STRATEGY s VERSION 1
        SYMBOLS
            fx = EXNESS:{EURUSD, XAUUSD} EVERY 15m WARMUP 20 BARS
            gold = EXNESS:XAUUSD EVERY 1h
        RULES
            FOR EACH m IN fx DO
              WHEN rsi(m.close, 14) < 30 AND POSITION.m = 0 THEN BUY m SIZING 1 PCT RISK
            WHEN gold.close > 0 THEN BUY gold SIZING 0.1
        """.trimIndent()

    @Test
    fun `a picked list becomes one stream per member and FOR EACH one rule per member`() {
        val ast = parse(picked)
        assertThat(ast.streams).containsExactly(
            StreamDecl("fx.EURUSD", "EXNESS", "EURUSD", "15m", 20),
            StreamDecl("fx.XAUUSD", "EXNESS", "XAUUSD", "15m", 20),
            StreamDecl("gold", "EXNESS", "XAUUSD", "1h"),
        )
        assertThat(ast.rules).hasSize(3)
        assertThat(
            ast.rules.map { ((it as WhenThen).action as Buy).stream },
        ).containsExactly("fx.EURUSD", "fx.XAUUSD", "gold")
        assertThat(ast.rules[0].line).isEqualTo(6)
        assertThat(
            ast.rules[0].toString(),
        ).contains("StreamFieldRef(stream=fx.EURUSD, field=close)").contains("PositionRef(stream=fx.EURUSD)")
    }

    @Test
    fun `a picked list with a bracket FOR EACH over other streams leaves that form unchanged`() {
        val ast =
            parse(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    fx = EXNESS:{EURUSD} EVERY 15m
                    a = EXNESS:A EVERY 15m
                    b = EXNESS:B EVERY 15m
                RULES
                    FOR EACH s IN [a, b] DO WHEN s.close > 0 THEN BUY s SIZING 0.1
                """.trimIndent(),
            )
        assertThat(ast.rules.map { ((it as WhenThen).action as Buy).stream }).containsExactly("a", "b")
        assertThat(ast.streams.map { it.alias }).containsExactly("fx.EURUSD", "a", "b")
    }

    @Test
    fun `a union of picked sets contributes its operands' members and no stream of its own`() {
        val ast =
            parse(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    fx = EXNESS:{EURUSD, XAUUSD} EVERY 15m
                    crypto = BINANCE:{BTCUSDT} EVERY 15m
                    majors = fx + crypto
                RULES
                    FOR EACH m IN majors DO WHEN m.close > 0 THEN BUY m SIZING 0.1
                    FOR EACH m IN crypto DO WHEN m.close < 0 THEN BUY m SIZING 0.1
                """.trimIndent(),
            )
        assertThat(ast.streams.map { it.alias }).containsExactly("fx.EURUSD", "fx.XAUUSD", "crypto.BTCUSDT")
        assertThat(ast.rules.map { ((it as WhenThen).action as Buy).stream })
            .containsExactly("fx.EURUSD", "fx.XAUUSD", "crypto.BTCUSDT", "crypto.BTCUSDT")
    }

    private val filtered =
        """
        STRATEGY s VERSION 1
        SYMBOLS
            stocks = ALPACA:* WHERE category = "us_equity" AND contract_size > 5 TOP 50 BY symbol EVERY 15m
            fx = EXNESS:{EURUSD} EVERY 15m
            both = stocks + fx
        RULES
            FOR EACH m IN stocks DO WHEN m.close > 0 THEN BUY m SIZING 0.1
            FOR EACH m IN both DO WHEN m.close > 1 THEN BUY m SIZING 0.1
            FOR EACH m IN fx DO WHEN m.close > 2 THEN BUY m SIZING 0.1
        """.trimIndent()

    @Test
    fun `a filtered set and the unions and loops over it wait for a catalog`() {
        val ast = parse(filtered)
        assertThat(ast.streams).containsExactly(
            StreamDecl(
                "stocks",
                SET_BROKER,
                "ALPACA:* WHERE (category = \"us_equity\" AND contract_size > 5) TOP 50 BY symbol DESC",
                "15m",
            ),
            StreamDecl("fx.EURUSD", "EXNESS", "EURUSD", "15m"),
            StreamDecl("both", SET_BROKER, "stocks + fx", "15m"),
        )
        assertThat(ast.rules).hasSize(3)
        val overStocks = ast.rules[0] as ForEachSet
        assertThat(overStocks.set).isEqualTo("stocks")
        assertThat(overStocks.iterVar).isEqualTo("m")
        assertThat(overStocks.line).isEqualTo(7)
        assertThat((ast.rules[1] as ForEachSet).set).isEqualTo("both")
        assertThat(((ast.rules[2] as WhenThen).action as Buy).stream).isEqualTo("fx.EURUSD")
    }

    @Test
    fun `a later pass with members resolves what the parser left`() {
        val ast = parse(filtered)
        val resolver =
            SymbolSetExpansion.Resolver { alias, spec ->
                assertThat(alias).isEqualTo("stocks")
                assertThat(spec).isInstanceOf(SymbolSetSpec.Filtered::class.java)
                listOf("AAPL", "MSFT")
            }
        val expanded = SymbolSetExpansion.expand(ast.streams, ast.rules, resolver)
        assertThat(expanded.streams.map { it.alias }).containsExactly("stocks.AAPL", "stocks.MSFT", "fx.EURUSD")
        assertThat(expanded.streams[0]).isEqualTo(StreamDecl("stocks.AAPL", "ALPACA", "AAPL", "15m"))
        assertThat(expanded.rules.map { ((it as WhenThen).action as Buy).stream })
            .containsExactly("stocks.AAPL", "stocks.MSFT", "stocks.AAPL", "stocks.MSFT", "fx.EURUSD", "fx.EURUSD")
    }

    @Test
    fun `a union deduplicates members two sets share`() {
        val ast =
            parse(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    a = X:{P, Q} EVERY 1h
                    b = X:{Q, R} EVERY 1h
                    u = a + b
                RULES
                    FOR EACH m IN u DO WHEN m.close > 0 THEN BUY m SIZING 1
                """.trimIndent(),
            )
        assertThat(ast.rules.map { ((it as WhenThen).action as Buy).stream }).containsExactly("a.P", "a.Q", "b.R")
    }

    @Test
    fun `set mistakes are parse errors naming the line`() {
        val head = "STRATEGY s VERSION 1\nSYMBOLS\n"
        assertThat(
            errors(
                head +
                    "    fx = EXNESS:{EURUSD} EVERY 15m\nRULES\n    FOR EACH m IN gold DO WHEN m.close > 0 THEN BUY m SIZING 1",
            ),
        ).containsExactly(
            "5: FOR EACH m IN gold: 'gold' is not a symbol set declared in SYMBOLS (a list of streams needs brackets: [gold])",
        )
        assertThat(
            errors(
                head +
                    "    gold = EXNESS:XAUUSD EVERY 1h\nRULES\n    FOR EACH m IN gold DO WHEN m.close > 0 THEN BUY m SIZING 1",
            ),
        ).singleElement()
            .asString()
            .contains("'gold' is not a symbol set")
        assertThat(
            errors(
                head +
                    "    a = X:{P} EVERY 1h\n    b = X:{Q} EVERY 4h\n    u = a + b\nRULES\n    WHEN a.P.close > 0 THEN BUY a.P SIZING 1",
            ),
        ).anySatisfy { assertThat(it).contains("union 'u': its sets must share EVERY, got 1h, 4h") }
        assertThat(
            errors(
                head +
                    "    a = X:{P} EVERY 1h\n    g = X:G EVERY 1h\n    u = a + g\nRULES\n    WHEN g.close > 0 THEN BUY g SIZING 1",
            ),
        ).anySatisfy { assertThat(it).contains("union 'u': 'g' is not a symbol set declared above it") }
        assertThat(
            errors(
                head + "    a = X:{P} EVERY 1h\nRULES\n    FOR EACH m IN a, b DO WHEN m.close > 0 THEN BUY m SIZING 1",
            ),
        ).anySatisfy {
            assertThat(
                it,
            ).contains("expected '[' to open stream alias list (a symbol set needs none: IN <set> DO)")
        }
        assertThat(errors(head + "    a = X:{P P} EVERY 1h\nRULES\n    WHEN a.P.close > 0 THEN BUY a.P SIZING 1"))
            .anySatisfy { assertThat(it).contains("expected ',' or '}' in the symbol list") }
    }
}
