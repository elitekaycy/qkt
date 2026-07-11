package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChildPriceResolverTest {
    private val resolver = ChildPriceResolver(ExprCompiler())
    private val ec =
        EvalContext(
            candle =
                Candle(
                    "BACKTEST:BTCUSDT",
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    0L,
                    1L,
                ),
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `AT returns the absolute price`() {
        val r = resolver.compile(ChildAt(NumLit(BigDecimal("105"))), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("105")
    }

    @Test
    fun `BY for stop loss on long subtracts distance from entry`() {
        val r = resolver.compile(ChildBy(NumLit(BigDecimal("5"))), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("95")
    }

    @Test
    fun `BY for stop loss on short adds distance to entry`() {
        val r = resolver.compile(ChildBy(NumLit(BigDecimal("5"))), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(ec, side = Side.SELL, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("105")
    }

    @Test
    fun `BY for take profit on long adds distance`() {
        val r = resolver.compile(ChildBy(NumLit(BigDecimal("10"))), kind = ChildKind.TAKE_PROFIT)
        assertThat(r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("110")
    }

    @Test
    fun `PCT for stop loss on long treats the value as percentage points`() {
        val r = resolver.compile(ChildPct(NumLit(BigDecimal("5"))), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("95")
    }

    @Test
    fun `PCT rejects a stop distance of fifty percent or more`() {
        assertThatThrownBy {
            resolver.compile(ChildPct(NumLit(BigDecimal("50"))), kind = ChildKind.STOP_LOSS)
        }.hasMessageContaining("less than 50")
    }

    @Test
    fun `RR for take profit uses stop distance`() {
        val r = resolver.compile(ChildRr(NumLit(BigDecimal("3"))), kind = ChildKind.TAKE_PROFIT)
        assertThat(r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = BigDecimal("5")))
            .isEqualByComparingTo("115")
    }

    @Test
    fun `AT returns null when expression is undefined`() {
        val r = resolver.compile(ChildAt(AccountRef("last_trade_pnl")), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = null)).isNull()
    }

    @Test
    fun `BY returns null when expression is undefined`() {
        val r = resolver.compile(ChildBy(AccountRef("last_trade_pnl")), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = null)).isNull()
    }

    @Test
    fun `PCT returns null when expression is undefined`() {
        val r = resolver.compile(ChildPct(AccountRef("last_trade_pnl")), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = null)).isNull()
    }

    @Test
    fun `RR returns null when expression is undefined`() {
        val r = resolver.compile(ChildRr(AccountRef("last_trade_pnl")), kind = ChildKind.TAKE_PROFIT)
        assertThat(r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = BigDecimal("5"))).isNull()
    }

    @Test
    fun `RR without stop distance errors`() {
        val r = resolver.compile(ChildRr(NumLit(BigDecimal("3"))), kind = ChildKind.TAKE_PROFIT)
        assertThatThrownBy { r.evaluate(ec, side = Side.BUY, entry = BigDecimal("100"), stopDistance = null) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `RR for stop loss errors at compile time`() {
        assertThatThrownBy {
            resolver.compile(ChildRr(NumLit(BigDecimal("3"))), kind = ChildKind.STOP_LOSS)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
