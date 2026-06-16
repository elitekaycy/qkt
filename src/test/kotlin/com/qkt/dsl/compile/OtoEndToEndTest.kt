package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * OTO (`ON_FILL`) — a parent BUY/SELL whose children are placed only when the parent fills.
 * The DSL emits a single [OrderRequest.OTO]; the OrderManager owns the place-on-fill lifecycle.
 */
class OtoEndToEndTest {
    private fun compile(src: String): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    private fun run(strategy: DslCompiledStrategy): List<Signal> {
        val hub = CandleHub()
        strategy.declaredStreams.values.forEach { hub.register(it, retention = 10, strategyId = "t") }
        val signals = mutableListOf<Signal>()
        strategy.bindToHub(hub, testStrategyContext()) { signals.add(it) }
        // Close one 1m bar on each stream; gold's close (150) fires the rule.
        hub.feed(Tick("EXNESS:XAGUSD", BigDecimal("25"), 0L))
        hub.feed(Tick("EXNESS:XAGUSD", BigDecimal("25"), 60_000L))
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("150"), 0L))
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("150"), 60_000L))
        return signals
    }

    private val symbols =
        """
        SYMBOLS
          gold   = EXNESS:XAUUSD EVERY 1m,
          silver = EXNESS:XAGUSD EVERY 1m
        """.trimIndent()

    @Test
    fun `parent with a market hedge child emits an OTO`() {
        val src =
            """
            STRATEGY oto VERSION 1
            $symbols
            RULES
              WHEN gold.close > 100
              THEN BUY gold SIZING 1 ON_FILL { SELL silver SIZING 2 }
            """.trimIndent()
        val oto =
            run(compile(src)).filterIsInstance<Signal.Submit>().single().request as OrderRequest.OTO

        val parent = oto.parent as OrderRequest.Market
        assertThat(parent.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(parent.side).isEqualTo(Side.BUY)
        assertThat(parent.quantity).isEqualByComparingTo("1")

        val child = oto.children.single() as OrderRequest.Market
        assertThat(child.symbol).isEqualTo("EXNESS:XAGUSD")
        assertThat(child.side).isEqualTo(Side.SELL)
        assertThat(child.quantity).isEqualByComparingTo("2")
    }

    @Test
    fun `a child prices itself relative to the parent fill via entry`() {
        // Parent gold market entry is 150; the scale-in child's limit is entry - 10 = 140.
        val src =
            """
            STRATEGY otoentry VERSION 1
            $symbols
            RULES
              WHEN gold.close > 100
              THEN BUY gold SIZING 1
                ON_FILL { BUY gold SIZING 0.5 ORDER_TYPE = LIMIT AT entry - 10 }
            """.trimIndent()
        val oto =
            run(compile(src)).filterIsInstance<Signal.Submit>().single().request as OrderRequest.OTO

        val child = oto.children.single() as OrderRequest.Limit
        assertThat(child.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(child.side).isEqualTo(Side.BUY)
        assertThat(child.quantity).isEqualByComparingTo("0.5")
        assertThat(child.limitPrice).isEqualByComparingTo("140")
    }

    @Test
    fun `multiple children both attach to the parent`() {
        val src =
            """
            STRATEGY otomulti VERSION 1
            $symbols
            RULES
              WHEN gold.close > 100
              THEN BUY gold SIZING 1
                ON_FILL {
                  SELL silver SIZING 2 ;
                  BUY gold SIZING 0.5 ORDER_TYPE = STOP AT entry + 5
                }
            """.trimIndent()
        val oto =
            run(compile(src)).filterIsInstance<Signal.Submit>().single().request as OrderRequest.OTO
        assertThat(oto.children).hasSize(2)
        assertThat((oto.children[0] as OrderRequest.Market).symbol).isEqualTo("EXNESS:XAGUSD")
        val stopChild = oto.children[1] as OrderRequest.Stop
        assertThat(stopChild.stopPrice).isEqualByComparingTo("155") // entry 150 + 5
    }

    @Test
    fun `OTO cannot combine with a bracket`() {
        val src =
            """
            STRATEGY otobad VERSION 1
            $symbols
            RULES
              WHEN gold.close > 100
              THEN BUY gold SIZING 1
                BRACKET { STOP LOSS BY 60, TAKE PROFIT BY 10 }
                ON_FILL { SELL silver SIZING 1 }
            """.trimIndent()
        assertThatThrownBy { compile(src) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `entry is rejected outside an ON_FILL child price`() {
        // `entry` only parses to a parent-fill reference inside ON_FILL; elsewhere it is a plain
        // identifier (unknown stream), so the strategy fails to compile.
        val src =
            """
            STRATEGY otobadentry VERSION 1
            $symbols
            RULES
              WHEN gold.close > entry THEN BUY gold SIZING 1
            """.trimIndent()
        assertThatThrownBy { compile(src) }.isInstanceOf(Exception::class.java)
    }
}
