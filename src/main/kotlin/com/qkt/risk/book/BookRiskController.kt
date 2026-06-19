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
    @Volatile
    private var current: BookRiskState = BookRiskState(capital, Money.ZERO, emptyMap(), config.limits)

    fun onSample(snapshot: BookSnapshot) {
        current =
            BookRiskState(
                capital = capital,
                grossExposure = snapshot.exposure.gross,
                perSymbolNet = snapshot.exposure.perSymbolNet,
                limits = config.limits,
            )
    }

    fun state(): BookRiskState = current
}
