package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.TradesRef
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.risk.PacerView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** ACCOUNT, STREAK, TRADES and COOLDOWN reads compiled by [AccountStateCompiler]. */
class AccountStateCompilerTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    private fun ctxWithPnl(
        realized: BigDecimal = BigDecimal.ZERO,
        unrealizedTotal: BigDecimal = BigDecimal.ZERO,
    ): EvalContext {
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = realized

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = unrealizedTotal

                override fun total(): BigDecimal = realized.add(unrealizedTotal)

                override fun equity(): BigDecimal = realized.add(unrealizedTotal)

                override fun balance(): BigDecimal = realized
            }
        return EvalContext(
            candle = candle,
            streams = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(pnl = pnl),
        )
    }

    @Test
    fun `ACCOUNT realized_pnl reads from pnl view`() {
        val v =
            ExprCompiler()
                .compile(AccountRef("realized_pnl"))
                .evaluate(ctxWithPnl(realized = BigDecimal("123.45"))) as Value.Num
        assertThat(v.v).isEqualByComparingTo("123.45")
    }

    @Test
    fun `ACCOUNT unrealized_pnl reads from pnl view`() {
        val v =
            ExprCompiler()
                .compile(AccountRef("unrealized_pnl"))
                .evaluate(ctxWithPnl(unrealizedTotal = BigDecimal("7.5"))) as Value.Num
        assertThat(v.v).isEqualByComparingTo("7.5")
    }

    @Test
    fun `ACCOUNT total_pnl reads from pnl view`() {
        val v =
            ExprCompiler()
                .compile(AccountRef("total_pnl"))
                .evaluate(
                    ctxWithPnl(realized = BigDecimal("10"), unrealizedTotal = BigDecimal("5")),
                ) as Value.Num
        assertThat(v.v).isEqualByComparingTo("15")
    }

    @Test
    fun `unsupported ACCOUNT field is rejected at compile time`() {
        assertThatThrownBy { ExprCompiler().compile(AccountRef("drawdown")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `STREAK accessors read current trade history streak state`() {
        val history = com.qkt.pnl.TradeHistory()
        history.recordTrade("test", 100L, BigDecimal("-5"), "BACKTEST:BTCUSDT")
        history.recordTrade("test", 200L, BigDecimal("10.50"), "BACKTEST:BTCUSDT")
        history.recordTrade("test", 300L, BigDecimal("15.25"), "BACKTEST:BTCUSDT")
        val ec =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(tradeHistory = com.qkt.pnl.TradeHistoryViewImpl(history, "test")),
            )

        fun read(field: String) = (ExprCompiler().compile(StreakRef(field)).evaluate(ec) as Value.Num).v

        assertThat(read("wins")).isEqualByComparingTo("2")
        assertThat(read("losses")).isEqualByComparingTo("0")
        assertThat(read("banked")).isEqualByComparingTo("25.75")
    }

    @Test
    fun `unsupported STREAK field is rejected at compile time`() {
        assertThatThrownBy { ExprCompiler().compile(StreakRef("drawdown")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `TRADES and COOLDOWN accessors read pacer state`() {
        val pacer =
            object : PacerView {
                override fun tradesToday(nowMs: Long): Int = 4

                override fun cooldownRemainingSeconds(nowMs: Long): Long = 90
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(pacer = pacer),
            )

        val trades = ExprCompiler().compile(TradesRef("today")).evaluate(ec) as Value.Num
        val cooldown = ExprCompiler().compile(CooldownRef("remaining_s")).evaluate(ec) as Value.Num

        assertThat(trades.v).isEqualByComparingTo("4")
        assertThat(cooldown.v).isEqualByComparingTo("90")
    }

    @Test
    fun `unsupported pacer fields are rejected at compile time`() {
        assertThatThrownBy { ExprCompiler().compile(TradesRef("week")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ExprCompiler().compile(CooldownRef("remaining_ms")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ACCOUNT equity reads from pnl view`() {
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = BigDecimal.ZERO

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                override fun total(): BigDecimal = BigDecimal.ZERO

                override fun equity(): BigDecimal = BigDecimal("10250")

                override fun balance(): BigDecimal = BigDecimal("10000")
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(pnl = pnl),
            )
        val v = ExprCompiler().compile(AccountRef("equity")).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("10250")
    }

    @Test
    fun `ACCOUNT balance reads from pnl view`() {
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = BigDecimal.ZERO

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                override fun total(): BigDecimal = BigDecimal.ZERO

                override fun equity(): BigDecimal = BigDecimal("10250")

                override fun balance(): BigDecimal = BigDecimal("10000")
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(pnl = pnl),
            )
        val v = ExprCompiler().compile(AccountRef("balance")).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("10000")
    }
}
