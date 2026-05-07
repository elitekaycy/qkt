package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal

data class WinLossStats(
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val largestWin: BigDecimal,
    val largestLoss: BigDecimal,
    val maxConsecutiveLosses: Int,
)

fun winLossStats(realizeds: List<BigDecimal>): WinLossStats {
    val wins = realizeds.filter { it.signum() > 0 }
    val losses = realizeds.filter { it.signum() < 0 }

    val avgWin =
        if (wins.isEmpty()) {
            Money.ZERO
        } else {
            wins
                .fold(Money.ZERO) { a, v -> a.add(v) }
                .divide(BigDecimal(wins.size), Money.CONTEXT)
        }

    val avgLoss =
        if (losses.isEmpty()) {
            Money.ZERO
        } else {
            losses
                .fold(Money.ZERO) { a, v -> a.add(v) }
                .divide(BigDecimal(losses.size), Money.CONTEXT)
        }

    val largestWin = wins.maxOrNull() ?: Money.ZERO
    val largestLoss = losses.minOrNull() ?: Money.ZERO

    var maxRun = 0
    var run = 0
    for (v in realizeds) {
        if (v.signum() < 0) {
            run += 1
            if (run > maxRun) maxRun = run
        } else {
            run = 0
        }
    }

    return WinLossStats(
        avgWin = avgWin.setScale(Money.SCALE, Money.ROUNDING),
        avgLoss = avgLoss.setScale(Money.SCALE, Money.ROUNDING),
        largestWin = largestWin.setScale(Money.SCALE, Money.ROUNDING),
        largestLoss = largestLoss.setScale(Money.SCALE, Money.ROUNDING),
        maxConsecutiveLosses = maxRun,
    )
}
