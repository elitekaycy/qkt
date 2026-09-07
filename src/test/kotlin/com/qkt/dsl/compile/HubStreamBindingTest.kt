package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Binding a qkt-data-hub dataset into a strategy: parsing the dotted name, expanding the alias
 * into one hidden stream per field actually read, and refusing to trade a dataset.
 *
 * The invariant these guard is the one the whole binding is built around: a strategy that binds
 * no hub stream must be unaffected. Every hub-specific behaviour keys off the alias's venue token
 * resolved at compile time, never off a runtime string test that could match something else.
 */
class HubStreamBindingTest {
    private fun compile(source: String): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(source) as ParseResult.Success).value) as DslCompiledStrategy

    private fun ast(source: String) = (Dsl.parse(source) as ParseResult.Success).value

    private val strategy =
        """
        STRATEGY hubBinding VERSION 1

        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 5m
            cal  = HUB:cal.high_impact.USD EVERY 1d

        RULES
            WHEN cal.surprise > 0.05 AND POSITION.gold = 0
            THEN BUY gold SIZING 0.01

            WHEN POSITION.gold > 0 AND cal.forecast IS NULL
            THEN CLOSE gold
        """.trimIndent()

    @Test
    fun `a dotted hub dataset name parses as one symbol`() {
        // Expansion happens at the parse boundary, so what a caller sees is already per-field;
        // the dotted dataset name survives intact as the prefix of every field stream.
        val streams = ast(strategy).streams.filter { it.broker == "HUB" }
        assertThat(streams).isNotEmpty
        assertThat(streams.map { it.symbol }).allMatch { it.startsWith("cal.high_impact.USD/") }
        assertThat(streams.map { it.alias }).containsExactlyInAnyOrder("cal/surprise", "cal/forecast")
    }

    @Test
    fun `each referenced field becomes its own hidden stream`() {
        val compiled = compile(strategy)
        val streams = compiled.declaredStreams

        // The dataset alias itself is gone: it names nothing with a value of its own, and asking
        // the store for it would be asking for a stream that cannot exist.
        assertThat(streams).doesNotContainKey("cal")
        assertThat(streams["cal/surprise"]?.qktSymbol).isEqualTo("HUB:cal.high_impact.USD/surprise")
        assertThat(streams["cal/forecast"]?.qktSymbol).isEqualTo("HUB:cal.high_impact.USD/forecast")
    }

    @Test
    fun `a field the strategy never reads is not expanded`() {
        // A dataset may be wide. Expanding every declared field would register slots, warmup and
        // feed reads for data no rule looks at.
        val streams = compile(strategy).declaredStreams
        assertThat(streams.keys.filter { it.startsWith("cal/") })
            .containsExactlyInAnyOrder("cal/surprise", "cal/forecast")
    }

    @Test
    fun `a strategy that binds no hub stream gains no hidden streams`() {
        val plain =
            """
            STRATEGY plain VERSION 1

            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 5m

            RULES
                WHEN gold.close > 0 AND POSITION.gold = 0
                THEN BUY gold SIZING 0.01
            """.trimIndent()
        val streams = compile(plain).declaredStreams
        assertThat(streams).containsOnlyKeys("gold")
        assertThat(streams.values.map { it.broker }).doesNotContain("HUB")
    }

    @Test
    fun `ordering a hub dataset is a compile error`() {
        // A dataset is a published statement, not a quote. There is nothing to buy.
        val tradesTheDataset =
            """
            STRATEGY tradesDataset VERSION 1

            SYMBOLS
                cal = HUB:cal.high_impact.USD EVERY 1d

            RULES
                WHEN cal.surprise > 0 THEN BUY cal SIZING 0.01
            """.trimIndent()
        assertThatThrownBy { compile(tradesTheDataset) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("read-only")
    }

    @Test
    fun `a candle field on a price stream is still validated`() {
        // The hub relaxation must not leak into ordinary streams: `gold.surprise` is still wrong.
        val bogus =
            """
            STRATEGY bogus VERSION 1

            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 5m

            RULES
                WHEN gold.surprise > 0 AND POSITION.gold = 0
                THEN BUY gold SIZING 0.01
            """.trimIndent()
        assertThatThrownBy { compile(bogus) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unknown stream field")
    }

    @Test
    fun `a hub observation is treated as an observation, not as a price`() {
        assertThat(isObservationSymbol("HUB:cal.high_impact.USD/surprise")).isTrue()
        assertThat(isObservationSymbol("MACRO:DFII10")).isTrue()
        assertThat(isObservationSymbol("EXNESS:XAUUSD")).isFalse()
        assertThat(isObservationSymbol("BACKTEST:BTCUSDT")).isFalse()
    }

    @Test
    fun `the hidden alias separator cannot collide with an author's alias`() {
        // A DSL alias is an identifier, so it can never contain '/'.
        assertThat(HubFieldExpansion.hiddenAlias("cal", "surprise")).isEqualTo("cal/surprise")
        assertThat(HubFieldExpansion.SEPARATOR.isLetterOrDigit()).isFalse()
    }

    @Test
    fun `an indicator over a hub field binds to the stream whose bars actually arrive`() {
        // The reason the expansion is an AST rewrite and not an evaluation-time lookup: an
        // indicator keys its updates on its root alias's symbol. Bound to the dataset alias it
        // would never see a bar and never warm; bound to the field stream it does.
        val source =
            """
            STRATEGY smoothed VERSION 1

            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 5m
                cal  = HUB:cal.high_impact.USD EVERY 1d

            RULES
                WHEN ema(cal.surprise, 3) > 0 AND POSITION.gold = 0
                THEN BUY gold SIZING 0.01
            """.trimIndent()
        val expanded = HubFieldExpansion.apply(ast(source)).ast
        val decl = expanded.streams.single { it.alias == "cal/surprise" }
        assertThat(decl.qktSymbol).isEqualTo("HUB:cal.high_impact.USD/surprise")
        val warmup = WarmupRequirements.compute(expanded)
        assertThat(warmup["cal/surprise"]).isEqualTo(3)
        assertThat(warmup).doesNotContainKey("cal")
    }

    @Test
    fun `a declared warmup on the dataset alias carries to every field stream`() {
        val source =
            """
            STRATEGY warmed VERSION 1

            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 5m
                cal  = HUB:cal.high_impact.USD EVERY 1d WARMUP 4 BARS

            RULES
                WHEN cal.surprise > 0 AND cal.forecast > 0 AND POSITION.gold = 0
                THEN BUY gold SIZING 0.01
            """.trimIndent()
        val expanded = HubFieldExpansion.apply(ast(source)).ast
        assertThat(expanded.streams.filter { it.alias.startsWith("cal/") }.map { it.warmupBars }).containsOnly(4)
    }
}
