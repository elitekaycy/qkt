package com.qkt.dsl.compile

import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerSnapshotTest {
    private val candle =
        Candle("BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    private fun ctx(store: SnapshotStore): EvalContext =
        EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
            snapshotStore = store,
        )

    @Test
    fun `Ref with SnapshotBuy reads slot via ruleSymbol`() {
        val store = SnapshotStore(emptyMap())
        store.captureSlot("BTCUSDT", "fast", SnapshotBuy, BigDecimal("123"))
        val compiled = ExprCompiler().compile(Ref("fast", SnapshotBuy), ruleSymbol = "BTCUSDT")
        val v = compiled.evaluate(ctx(store)) as Value.Num
        assertThat(v.v).isEqualByComparingTo("123")
    }

    @Test
    fun `unset slot returns Undefined`() {
        val store = SnapshotStore(emptyMap())
        val compiled = ExprCompiler().compile(Ref("fast", SnapshotOpen), ruleSymbol = "BTCUSDT")
        assertThat(compiled.evaluate(ctx(store))).isEqualTo(Value.Undefined)
    }

    @Test
    fun `Ref with SnapshotTPast reads rolling buffer`() {
        val store = SnapshotStore(maxRollingPerName = mapOf("close" to 2))
        store.pushRolling("BTCUSDT", "close", BigDecimal("100"))
        store.pushRolling("BTCUSDT", "close", BigDecimal("110"))
        store.pushRolling("BTCUSDT", "close", BigDecimal("120"))
        val compiled = ExprCompiler().compile(Ref("close", SnapshotTPast(2)), ruleSymbol = "BTCUSDT")
        val v = compiled.evaluate(ctx(store)) as Value.Num
        assertThat(v.v).isEqualByComparingTo("100")
    }

    @Test
    fun `snapshot Ref without ruleSymbol context errors`() {
        assertThatThrownBy {
            ExprCompiler().compile(Ref("fast", SnapshotBuy))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `bare Ref reaching ExprCompiler errors`() {
        assertThatThrownBy {
            ExprCompiler().compile(Ref("fast"), ruleSymbol = "BTCUSDT")
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
