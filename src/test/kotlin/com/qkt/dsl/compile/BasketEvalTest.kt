package com.qkt.dsl.compile

import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * #457 Task B6 — the basket's constituent sync-group computes the composite candle and
 * publishes it into the hub so `basket.close` reads it through the unchanged candle path.
 */
class BasketEvalTest {
    private val basketKey = HubKey("BASKET", "ANTIPODEAN", "1h")
    private val audSym = "EXNESS:AUDUSD"
    private val nzdSym = "EXNESS:NZDUSD"
    private val hour = 3_600_000L

    private val src =
        """
        STRATEGY eval VERSION 1
        SYMBOLS
            aud = EXNESS:AUDUSD EVERY 1h
            nzd = EXNESS:NZDUSD EVERY 1h
            antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
        RULES
            WHEN aud.close > 0 THEN LOG "warm"
        """.trimIndent()

    private fun compile(): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    private fun tick(
        symbol: String,
        ts: Long,
        price: String,
    ): Tick = Tick(symbol = symbol, price = BigDecimal(price), timestamp = ts, volume = BigDecimal.ONE)

    private fun boundHub(): CandleHub {
        val s = compile()
        val hub = CandleHub()
        s.declaredStreams.values.forEach { hub.register(it, retention = 10, strategyId = "test") }
        s.bindToHub(hub, testStrategyContext()) { _: Signal -> }
        return hub
    }

    @Test
    fun `basket close is undefined before the second aligned window`() {
        val hub = boundHub()
        // One aligned window: only the baseline is set, no composite candle published yet.
        hub.feed(tick(audSym, 0, "0.66"))
        hub.feed(tick(nzdSym, 0, "0.60"))
        hub.feed(tick(audSym, hour, "0.66")) // closes window 0 for aud
        hub.feed(tick(nzdSym, hour, "0.60")) // closes window 0 for nzd → first aligned fire
        assertThat(hub.latest(basketKey)).isNull()
    }

    @Test
    fun `basket close equals the composite index on the second aligned window`() {
        val hub = boundHub()
        // Window 0 baseline (aud 0.66, nzd 0.60); window 1 both +1% → index ~101.
        hub.feed(tick(audSym, 0, "0.66"))
        hub.feed(tick(nzdSym, 0, "0.60"))
        hub.feed(tick(audSym, hour, "0.6666"))
        hub.feed(tick(nzdSym, hour, "0.6060"))
        hub.feed(tick(audSym, 2 * hour, "0.6666")) // closes window 1
        hub.feed(tick(nzdSym, 2 * hour, "0.6060"))

        val candle = hub.latest(basketKey)
        assertThat(candle).isNotNull
        assertThat(candle!!.open).isEqualByComparingTo(BigDecimal("100"))
        assertThat(candle.close).isCloseTo(BigDecimal("101"), within(BigDecimal("0.0001")))
    }

    @Test
    fun `a compiled StreamFieldRef reads the published basket close`() {
        val hub = boundHub()
        val s = compile()
        hub.feed(tick(audSym, 0, "0.66"))
        hub.feed(tick(nzdSym, 0, "0.60"))
        hub.feed(tick(audSym, hour, "0.6666"))
        hub.feed(tick(nzdSym, hour, "0.6060"))
        hub.feed(tick(audSym, 2 * hour, "0.6666"))
        hub.feed(tick(nzdSym, 2 * hour, "0.6060"))

        val expr = ExprCompiler().compile(StreamFieldRef("antipodean", "close"))
        val ec =
            EvalContext(
                candle = hub.latest(basketKey)!!,
                streams = s.declaredStreams,
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
                snapshotStore = SnapshotStore(emptyMap()),
                hub = hub,
                currentAlias = null,
            )
        val value = expr.evaluate(ec)
        assertThat(value).isInstanceOf(Value.Num::class.java)
        assertThat((value as Value.Num).v).isCloseTo(BigDecimal("101"), within(BigDecimal("0.0001")))
    }
}
