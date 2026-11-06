package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.StreakRef
import com.qkt.marketdata.Candle
import com.qkt.pnl.TradeHistoryView
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.risk.RiskView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ACCOUNT trade-history, risk and position-count fields, and STREAK members, read through [AccountStateCompiler]. */
class AccountHistoryAndRiskFieldsTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    private val history =
        object : TradeHistoryView {
            override fun lastTradeAt(): Long? = 1_700_000_000_000L

            override fun lastTradePnl(): BigDecimal? = BigDecimal("-4.5")

            override fun winStreak(): Int = 3

            override fun lossStreak(): Int = 2

            override fun banked(): BigDecimal = BigDecimal("42.5")

            override fun tradesToday(nowEpochMs: Long): Int = if (nowEpochMs == NOW) 7 else -1

            override fun winsToday(nowEpochMs: Long): Int = if (nowEpochMs == NOW) 4 else -1

            override fun lossesToday(nowEpochMs: Long): Int = if (nowEpochMs == NOW) 3 else -1
        }

    private val risk =
        object : RiskView {
            override val halted = false
            override val haltReason: String? = null
            override val currentEquity = BigDecimal("950")
            override val equityPeak = BigDecimal("1000")
            override val drawdown = BigDecimal("0.05")
            override val realizedToday = BigDecimal("-12")
            override val realizedMonth = BigDecimal("30")
            override val globalHalted = false
            override val globalDrawdown = BigDecimal.ZERO
        }

    private val positions =
        object : StrategyPositionView {
            private val open =
                mapOf(
                    "A" to Position("A", BigDecimal.ONE, BigDecimal.TEN),
                    "B" to Position("B", BigDecimal("-2"), BigDecimal.TEN),
                )

            override fun positionFor(symbol: String): Position? = open[symbol]

            override fun allPositions(): Map<String, Position> = open
        }

    private val ctx =
        EvalContext(
            candle = candle,
            streams = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(tradeHistory = history, risk = risk, positions = positions),
            evaluationTimeMs = NOW,
        )

    private fun account(field: String): Value = ExprCompiler().compile(AccountRef(field)).evaluate(ctx)

    private fun streak(field: String): Value = ExprCompiler().compile(StreakRef(field)).evaluate(ctx)

    private fun Value.num(): BigDecimal = (this as Value.Num).v

    @Test
    fun `trade history fields read the strategy's trade history at the evaluation time`() {
        assertThat(account("last_trade_at").num()).isEqualByComparingTo("1700000000000")
        assertThat(account("last_trade_pnl").num()).isEqualByComparingTo("-4.5")
        assertThat(account("win_streak").num()).isEqualByComparingTo("3")
        assertThat(account("loss_streak").num()).isEqualByComparingTo("2")
        assertThat(account("trades_today").num()).isEqualByComparingTo("7")
        assertThat(account("wins_today").num()).isEqualByComparingTo("4")
        assertThat(account("losses_today").num()).isEqualByComparingTo("3")
    }

    @Test
    fun `last trade fields are undefined before the first trade`() {
        val empty =
            EvalContext(candle, emptyMap(), emptyMap(), testStrategyContext(), evaluationTimeMs = NOW)

        assertThat(ExprCompiler().compile(AccountRef("last_trade_at")).evaluate(empty)).isEqualTo(Value.Undefined)
        assertThat(ExprCompiler().compile(AccountRef("last_trade_pnl")).evaluate(empty)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `dd_pct exposes the drawdown fraction as a percentage`() {
        assertThat(account("dd_pct").num()).isEqualByComparingTo("5")
    }

    @Test
    fun `risk fields read the peak and the realized day and month`() {
        assertThat(account("equity_peak").num()).isEqualByComparingTo("1000")
        assertThat(account("realized_today").num()).isEqualByComparingTo("-12")
        assertThat(account("realized_month").num()).isEqualByComparingTo("30")
    }

    @Test
    fun `open_positions_count counts every open position of the strategy`() {
        assertThat(account("open_positions_count").num()).isEqualByComparingTo("2")
    }

    @Test
    fun `STREAK members read wins, losses and the banked profit`() {
        assertThat(streak("wins").num()).isEqualByComparingTo("3")
        assertThat(streak("losses").num()).isEqualByComparingTo("2")
        assertThat(streak("banked").num()).isEqualByComparingTo("42.5")
    }

    private companion object {
        const val NOW = 1_700_000_100_000L
    }
}
