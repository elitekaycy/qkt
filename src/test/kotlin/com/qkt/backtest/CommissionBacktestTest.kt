package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #335 — end-to-end check that a configured per-lot commission flows through a real backtest:
 * it is subtracted from realized PnL (so equity goes net) and surfaced as `commissionPaid`.
 */
class CommissionBacktestTest {
    private val symbol = "X"

    /** contractSize 1 so PnL is just priceDiff*qty; commissionPerLot drives the cost. */
    private fun registry(commissionPerLot: String): InstrumentRegistry {
        val meta =
            InstrumentMeta(
                qktSymbol = symbol,
                contractSize = BigDecimal.ONE,
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.01"),
                digits = 2,
                tradeStopsLevelPoints = 0,
                commissionPerLot = BigDecimal(commissionPerLot),
            )
        return object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = if (qktSymbol == symbol) meta else null
        }
    }

    /** Buys 1 lot on the first tick, sells 1 lot on the second — one round turn. */
    private fun roundTripStrategy(): Strategy =
        object : Strategy {
            private var step = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                when (step++) {
                    0 -> emit(Signal.Buy(symbol, Money.of("1.0")))
                    1 -> emit(Signal.Sell(symbol, Money.of("1.0")))
                }
            }
        }

    private fun ticks(): List<Tick> =
        listOf(
            Tick(symbol, Money.of("100"), 1_000L),
            Tick(symbol, Money.of("110"), 2_000L),
        )

    @Test
    fun `commission is netted from realized PnL and reported`() {
        val result =
            Backtest(
                strategies = listOf("s" to roundTripStrategy()),
                ticks = ticks(),
                initialTimestamp = 1_000L,
                startingBalance = BigDecimal("10000"),
                instruments = registry(commissionPerLot = "2.00"),
            ).run()

        // Gross close PnL = (110 - 100) * 1 lot * contractSize 1 = 10.
        // Commission = 2 fills * (1 lot * 2.00) = 4.00. Net realized = 10 - 4 = 6.
        assertThat(result.global.commissionPaid).isEqualByComparingTo("4.00")
        assertThat(result.global.realizedTotal).isEqualByComparingTo("6.00")
        // gross = net + commission.
        assertThat(result.global.realizedTotal.add(result.global.commissionPaid))
            .isEqualByComparingTo("10.00")
    }

    @Test
    fun `zero commission leaves PnL gross and reports zero cost`() {
        val result =
            Backtest(
                strategies = listOf("s" to roundTripStrategy()),
                ticks = ticks(),
                initialTimestamp = 1_000L,
                startingBalance = BigDecimal("10000"),
                instruments = registry(commissionPerLot = "0"),
            ).run()

        assertThat(result.global.commissionPaid).isEqualByComparingTo("0")
        assertThat(result.global.realizedTotal).isEqualByComparingTo("10.00")
    }
}
