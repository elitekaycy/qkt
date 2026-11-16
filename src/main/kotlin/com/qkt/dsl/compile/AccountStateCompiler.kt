package com.qkt.dsl.compile

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.TradesRef
import java.math.BigDecimal

/**
 * Compiles the strategy-wide account reads: `ACCOUNT.<field>` (P&L, trade history, risk),
 * `STREAK.<field>`, `TRADES.today` and `COOLDOWN.remaining_s`. Unsupported fields fail at
 * compile time; the closures read the strategy context's live views per evaluation.
 */
internal object AccountStateCompiler {
    private val pnlFields: Set<String> = DslVocabulary.accountPnlFields.toSet()
    private val historyFields: Set<String> = DslVocabulary.accountHistoryFields.toSet()
    private val riskFields: Set<String> = DslVocabulary.accountRiskFields.toSet()
    private val streakFields: Set<String> = DslVocabulary.streakMembers.toSet()

    fun compileAccountRef(ref: AccountRef): CompiledExpr {
        require(ref.field in pnlFields || ref.field in historyFields || ref.field in riskFields) {
            "Unsupported ACCOUNT field: ${ref.field}"
        }
        return CompiledExpr { ctx ->
            when (ref.field) {
                in pnlFields -> {
                    val pnl = ctx.strategyContext.pnl
                    Value.Num(
                        when (ref.field) {
                            "realized_pnl" -> pnl.realized()
                            "unrealized_pnl" -> pnl.unrealizedTotal()
                            "total_pnl" -> pnl.total()
                            "equity" -> pnl.equity()
                            "balance" -> pnl.balance()
                            else -> error("unreachable")
                        },
                    )
                }
                in historyFields -> {
                    val h = ctx.strategyContext.tradeHistory
                    val now = ctx.nowMs()
                    when (ref.field) {
                        "last_trade_at" -> h.lastTradeAt()?.let { Value.Num(BigDecimal.valueOf(it)) } ?: Value.Undefined
                        "last_trade_pnl" -> h.lastTradePnl()?.let { Value.Num(it) } ?: Value.Undefined
                        "win_streak" -> Value.Num(BigDecimal.valueOf(h.winStreak().toLong()))
                        "loss_streak" -> Value.Num(BigDecimal.valueOf(h.lossStreak().toLong()))
                        "trades_today" -> Value.Num(BigDecimal.valueOf(h.tradesToday(now).toLong()))
                        "wins_today" -> Value.Num(BigDecimal.valueOf(h.winsToday(now).toLong()))
                        "losses_today" -> Value.Num(BigDecimal.valueOf(h.lossesToday(now).toLong()))
                        else -> error("unreachable")
                    }
                }
                "dd_pct" -> {
                    // RiskView.drawdown is a fraction (0.05 = 5%); expose as percent for ergonomic
                    // condition writing: `WHEN ACCOUNT.dd_pct > 5 ...`.
                    Value.Num(
                        ctx.strategyContext.risk.drawdown
                            .multiply(BigDecimal("100")),
                    )
                }
                "equity_peak" -> Value.Num(ctx.strategyContext.risk.equityPeak)
                // Closed-trade P&L since UTC midnight / the 1st of the UTC month (#855).
                "realized_today" -> Value.Num(ctx.strategyContext.risk.realizedToday)
                "realized_month" -> Value.Num(ctx.strategyContext.risk.realizedMonth)
                "open_positions_count" -> {
                    val count =
                        ctx.strategyContext.positions
                            .allPositions()
                            .size
                            .toLong()
                    Value.Num(BigDecimal.valueOf(count))
                }
                else -> error("unreachable")
            }
        }
    }

    fun compileStreakRef(ref: StreakRef): CompiledExpr {
        require(ref.field in streakFields) { "Unsupported STREAK field: ${ref.field}" }
        return CompiledExpr { ctx ->
            val history = ctx.strategyContext.tradeHistory
            when (ref.field) {
                "wins" -> Value.Num(BigDecimal.valueOf(history.winStreak().toLong()))
                "losses" -> Value.Num(BigDecimal.valueOf(history.lossStreak().toLong()))
                "banked" -> Value.Num(history.banked())
                else -> error("unreachable")
            }
        }
    }

    fun compileTradesRef(ref: TradesRef): CompiledExpr {
        require(ref.field in DslVocabulary.tradesMembers) { "Unsupported TRADES field: ${ref.field}" }
        return CompiledExpr { ctx ->
            Value.Num(
                BigDecimal.valueOf(
                    ctx.strategyContext.pacer
                        .tradesToday(ctx.nowMs())
                        .toLong(),
                ),
            )
        }
    }

    fun compileCooldownRef(ref: CooldownRef): CompiledExpr {
        require(ref.field in DslVocabulary.cooldownMembers) { "Unsupported COOLDOWN field: ${ref.field}" }
        return CompiledExpr { ctx ->
            Value.Num(BigDecimal.valueOf(ctx.strategyContext.pacer.cooldownRemainingSeconds(ctx.nowMs())))
        }
    }
}
