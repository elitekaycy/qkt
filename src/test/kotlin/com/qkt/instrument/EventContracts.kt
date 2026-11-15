package com.qkt.instrument

import java.math.BigDecimal

/** A Kalshi-shaped ladder contract ("S&P 500 at 7,425 or above at 15:00") and a registry that knows it. */
internal object EventContracts {
    const val SYMBOL = "KALSHI:KXINXU_26NOV06H1500_T7425"

    fun terms(
        payout: BigDecimal = BigDecimal.ONE,
        outcome: BigDecimal? = null,
    ) = EventTerms(
        root = "KALSHI:KXINXU",
        event = "KXINXU-26NOV06H1500",
        predicate = StrikePredicate(StrikeType.GREATER_OR_EQUAL, BigDecimal("7425"), null),
        openAtMs = 1_794_002_400_000L,
        closeAtMs = 1_794_006_000_000L,
        expiryMs = 1_794_006_000_000L,
        payout = payout,
        outcome = outcome,
    )

    fun meta(
        symbol: String = SYMBOL,
        terms: EventTerms = terms(),
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal.ONE,
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal.ONE,
        volumeMax = null,
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
        currency = "USD",
        derivative = terms,
    )

    fun registry(vararg metas: InstrumentMeta): InstrumentRegistry {
        val table = metas.associateBy { it.qktSymbol }
        return object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) = table[qktSymbol]
        }
    }
}
