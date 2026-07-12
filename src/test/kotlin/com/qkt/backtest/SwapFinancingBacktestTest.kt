package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.risk.rules.MaxDailyLoss
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SwapFinancingBacktestTest {
    private val symbol = "X"

    private fun timestamp(value: String): Long = Instant.parse(value).toEpochMilli()

    private fun registry(longPoints: String): InstrumentRegistry {
        val meta =
            InstrumentMeta(
                qktSymbol = symbol,
                contractSize = BigDecimal("100"),
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.01"),
                digits = 2,
                tradeStopsLevelPoints = 0,
                swapLongPoints = BigDecimal(longPoints),
                swapRolloverHourUtc = 21,
                swapTripleDay = DayOfWeek.WEDNESDAY,
            )
        return object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = meta.takeIf { qktSymbol == symbol }
        }
    }

    private fun buyAndHold(): Strategy =
        object : Strategy {
            private var bought = false

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (!bought) {
                    bought = true
                    emit(Signal.Buy(symbol, BigDecimal.ONE))
                }
            }
        }

    private fun roundTrip(): Strategy =
        object : Strategy {
            private var step = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                when (step++) {
                    0 -> emit(Signal.Buy(symbol, BigDecimal.ONE))
                    1 -> emit(Signal.Sell(symbol, BigDecimal.ONE))
                }
            }
        }

    @Test
    fun `gap accrues ordinary and triple swap into global and strategy reports`() {
        val start = timestamp("2026-07-07T20:00:00Z")
        val result =
            Backtest(
                strategies = listOf("s" to buyAndHold()),
                ticks =
                    listOf(
                        Tick(symbol, Money.of("100"), start),
                        Tick(symbol, Money.of("100"), timestamp("2026-07-08T21:00:00Z")),
                    ),
                initialTimestamp = start,
                startingBalance = BigDecimal("10000"),
                instruments = registry(longPoints = "-1"),
            ).run()

        // Tuesday: -1. Wednesday triple rollover: -3.
        assertThat(result.global.realizedTotal).isEqualByComparingTo("-4")
        assertThat(result.global.swapPaid).isEqualByComparingTo("4")
        assertThat(result.global.dailyPnL)
            .containsEntry(LocalDate.parse("2026-07-07"), BigDecimal("-1.00000000"))
            .containsEntry(LocalDate.parse("2026-07-08"), BigDecimal("-3.00000000"))
        assertThat(result.perStrategy.getValue("s").swapPaid).isEqualByComparingTo("4")
        assertThat(result.perStrategy.getValue("s").realizedTotal).isEqualByComparingTo("-4")
    }

    @Test
    fun `position closed by boundary tick still pays that rollover`() {
        val start = timestamp("2026-07-06T20:00:00Z")
        val result =
            Backtest(
                strategies = listOf("s" to roundTrip()),
                ticks =
                    listOf(
                        Tick(symbol, Money.of("100"), start),
                        Tick(symbol, Money.of("100"), timestamp("2026-07-06T21:00:00Z")),
                    ),
                initialTimestamp = start,
                startingBalance = BigDecimal("10000"),
                instruments = registry(longPoints = "-1"),
            ).run()

        assertThat(result.finalPositions).doesNotContainKey(symbol)
        assertThat(result.global.realizedTotal).isEqualByComparingTo("-1")
        assertThat(result.global.swapPaid).isEqualByComparingTo("1")
    }

    @Test
    fun `swap debit is visible to daily loss halt rule`() {
        val start = timestamp("2026-07-06T20:00:00Z")
        val result =
            Backtest(
                strategies = listOf("s" to buyAndHold()),
                haltRules = listOf(MaxDailyLoss(BigDecimal("0.50"))),
                ticks =
                    listOf(
                        Tick(symbol, Money.of("100"), start),
                        Tick(symbol, Money.of("100"), timestamp("2026-07-06T21:00:00Z")),
                    ),
                initialTimestamp = start,
                startingBalance = BigDecimal("10000"),
                instruments = registry(longPoints = "-1"),
            ).run()

        assertThat(result.halts).hasSize(1)
        assertThat(result.halts.single().reason).contains("daily loss 1.00000000 exceeds max 0.50")
    }

    @Test
    fun `zero swap metadata preserves realized pnl`() {
        val start = timestamp("2026-07-06T20:00:00Z")
        val result =
            Backtest(
                strategies = listOf("s" to buyAndHold()),
                ticks =
                    listOf(
                        Tick(symbol, Money.of("100"), start),
                        Tick(symbol, Money.of("100"), timestamp("2026-07-06T21:00:00Z")),
                    ),
                initialTimestamp = start,
                startingBalance = BigDecimal("10000"),
                instruments = registry(longPoints = "0"),
            ).run()

        assertThat(result.global.realizedTotal).isEqualByComparingTo("0")
        assertThat(result.global.swapPaid).isEqualByComparingTo("0")
    }
}
