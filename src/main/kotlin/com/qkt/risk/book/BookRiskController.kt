package com.qkt.risk.book

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * The book-risk brain. Fed a [BookSnapshot] each sample (by the measurement monitor), it refreshes an
 * immutable [BookRiskState] the pre-trade gate reads. Decisions are deterministic functions of the
 * snapshot + config, so the same controller produces the same calls in backtest and live. Shared
 * across child sessions live; single instance per engine in backtest.
 */
class BookRiskController(
    private val config: BookRiskConfig,
    private val capital: BigDecimal,
) {
    private val ladder = config.deRisk?.let { DeRiskLadder(it.ladder) }
    private var peakEquity = capital

    @Volatile
    private var current: BookRiskState = BookRiskState(capital, Money.ZERO, emptyMap(), config.limits)

    fun onSample(snapshot: BookSnapshot) {
        if (snapshot.bookEquity > peakEquity) peakEquity = snapshot.bookEquity
        val drawdown =
            if (peakEquity.signum() > 0) {
                peakEquity.subtract(snapshot.bookEquity).divide(peakEquity, Money.CONTEXT).max(Money.ZERO)
            } else {
                Money.ZERO
            }
        val factor = ladder?.factorFor(drawdown) ?: BigDecimal.ONE
        current =
            BookRiskState(
                capital = capital,
                grossExposure = snapshot.exposure.gross,
                perSymbolNet = snapshot.exposure.perSymbolNet,
                limits = config.limits,
                deRiskFactor = factor,
            )
    }

    fun state(): BookRiskState = current
}
