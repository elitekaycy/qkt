package com.qkt.dsl.compile

import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CrossStreamConditionTest {
    private val btcKey = HubKey("BYBIT", "BTCUSDT", "1m")
    private val goldKey = HubKey("INTERACTIVE", "XAUUSD", "1m")

    private fun candle(
        symbol: String,
        close: String,
        ts: Long = 0L,
    ): Candle =
        Candle(
            symbol,
            BigDecimal(close),
            BigDecimal(close),
            BigDecimal(close),
            BigDecimal(close),
            BigDecimal.ZERO,
            ts,
            ts + 60_000L,
        )

    private fun tick(
        symbol: String,
        price: String,
        ts: Long,
    ): Tick = Tick(symbol = symbol, price = BigDecimal(price), timestamp = ts, volume = BigDecimal.ONE)

    private fun ec(
        candle: Candle,
        hub: CandleHub,
    ): EvalContext =
        EvalContext(
            candle = candle,
            streams = mapOf("btc" to btcKey, "gold" to goldKey),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
            hub = hub,
        )

    @Test
    fun `cross-stream read returns Undefined when other alias has no closed candle`() {
        val hub = CandleHub()
        hub.register(btcKey, retention = 5)
        hub.register(goldKey, retention = 5)
        // current candle is gold; btc has no closes yet
        val expr = StreamFieldRef("btc", "close")
        val v = ExprCompiler().compile(expr).evaluate(ec(candle("XAUUSD", "2000"), hub))
        assertThat(v).isEqualTo(Value.Undefined)
    }

    @Test
    fun `cross-stream read returns hub latest close when current candle is other symbol`() {
        val hub = CandleHub()
        hub.register(btcKey, retention = 5)
        hub.register(goldKey, retention = 5)
        // Drive btc closes
        for (t in 0L..120_000L step 30_000L) hub.feed(tick("BTCUSDT", "100", t))
        // Now feed a gold candle — the current candle's symbol is gold, but rule reads btc.close
        val expr = StreamFieldRef("btc", "close")
        val v =
            ExprCompiler()
                .compile(expr)
                .evaluate(ec(candle("XAUUSD", "2000", ts = 150_000L), hub)) as Value.Num
        assertThat(v.v).isEqualByComparingTo("100")
    }

    @Test
    fun `same-symbol read uses live candle even when hub has older close`() {
        val hub = CandleHub()
        hub.register(btcKey, retention = 5)
        // Drive a btc close at price 100
        for (t in 0L..120_000L step 30_000L) hub.feed(tick("BTCUSDT", "100", t))
        // Live candle for btc with newer price 105
        val expr = StreamFieldRef("btc", "close")
        val v =
            ExprCompiler()
                .compile(expr)
                .evaluate(ec(candle("BTCUSDT", "105", ts = 150_000L), hub)) as Value.Num
        assertThat(v.v).isEqualByComparingTo("105")
    }

    @Test
    fun `cross-stream comparison evaluates true when both sides resolved`() {
        val hub = CandleHub()
        hub.register(btcKey, retention = 5)
        hub.register(goldKey, retention = 5)
        for (t in 0L..120_000L step 30_000L) hub.feed(tick("BTCUSDT", "120", t))
        val expr = CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("100")))
        val v =
            ExprCompiler()
                .compile(expr)
                .evaluate(ec(candle("XAUUSD", "2000", ts = 150_000L), hub))
        assertThat(v).isEqualTo(Value.Bool(true))
    }

    @Test
    fun `cross-stream comparison is Undefined while other side has no closed candle`() {
        val hub = CandleHub()
        hub.register(btcKey, retention = 5)
        hub.register(goldKey, retention = 5)
        val expr = CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("100")))
        val v = ExprCompiler().compile(expr).evaluate(ec(candle("XAUUSD", "2000"), hub))
        assertThat(v).isEqualTo(Value.Undefined)
    }
}
