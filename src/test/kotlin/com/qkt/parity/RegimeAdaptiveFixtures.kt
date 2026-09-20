package com.qkt.parity

import com.qkt.common.Money
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/** Symbol, tick tape and unit instrument registry shared by the regime-adaptive backtest parity tests. */
internal object RegimeAdaptiveFixtures {
    val sym = "BACKTEST:BTCUSDT"
    val firstTs = 1_700_000_000_000L

    /** Three ticks: first closes the default-regime bar, second closes the high-regime bar, third fills it. */
    fun ticks(): List<Tick> =
        listOf(
            Tick(sym, Money.of("100"), firstTs),
            Tick(sym, Money.of("300"), firstTs + 60_000L),
            Tick(sym, Money.of("300"), firstTs + 120_000L),
        )

    fun unitRegistry(): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal.ONE,
                    volumeStep = BigDecimal("0.001"),
                    volumeMin = BigDecimal("0.001"),
                    volumeMax = BigDecimal("1000"),
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }
}
