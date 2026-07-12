package com.qkt.pnl

import com.qkt.accounting.AccountingEngine
import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SwapFinancingBookTest {
    private val symbol = "BACKTEST:XAUUSD"
    private val positions = StrategyPositionTracker()
    private val prices = MarketPriceTracker().also { it.update(symbol, BigDecimal("2000")) }

    private fun timestamp(value: String): Long = Instant.parse(value).toEpochMilli()

    private fun registry(
        longPoints: String = "-2",
        shortPoints: String = "1",
    ): InstrumentRegistry {
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
                swapShortPoints = BigDecimal(shortPoints),
                swapRolloverHourUtc = 21,
                swapTripleDay = DayOfWeek.WEDNESDAY,
            )
        return object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = meta.takeIf { qktSymbol == symbol }
        }
    }

    private fun addLeg(
        id: String,
        side: Side,
        openedAt: Long,
        quantity: String = "0.5",
    ) {
        positions.addIndependentLeg(
            "s",
            PositionLeg(
                legId = id,
                symbol = symbol,
                side = side,
                quantity = BigDecimal(quantity),
                entryPrice = BigDecimal("2000"),
                openedAt = openedAt,
                role = LegRole.INDEPENDENT,
            ),
        )
    }

    private fun book(): SwapFinancingBook =
        SwapFinancingBook(
            instruments = registry(),
            strategyPositions = positions,
            accounting = AccountingEngine(),
            prices = prices,
            strategyIds = listOf("s"),
            symbols = listOf(symbol),
        )

    @Test
    fun `long debit and short credit accrue from full leg book`() {
        val opened = timestamp("2026-07-06T20:00:00Z")
        addLeg("long", Side.BUY, opened)
        addLeg("short", Side.SELL, opened)
        val accrued = mutableListOf<BigDecimal>()
        val book = book()

        book.accrueBetween(
            timestamp("2026-07-06T20:59:59Z"),
            timestamp("2026-07-06T21:00:00Z"),
        ) { _, _, amount -> accrued.add(amount) }

        // Long: -2 * .01 * 100 * .5 = -1. Short: +1 * .01 * 100 * .5 = +.5.
        assertThat(accrued).containsExactly(BigDecimal("-0.50000000"))
        assertThat(book.totalPaid()).isEqualByComparingTo("0.5")
        assertThat(book.totalPaidFor("s")).isEqualByComparingTo("0.5")
    }

    @Test
    fun `gap applies ordinary and triple rollovers chronologically but skips weekend`() {
        addLeg("long", Side.BUY, timestamp("2026-07-06T20:00:00Z"))
        val events = mutableListOf<Pair<Long, BigDecimal>>()
        val book = book()

        book.accrueBetween(
            timestamp("2026-07-06T20:59:59Z"),
            timestamp("2026-07-13T21:00:00Z"),
        ) { _, time, amount -> events.add(time to amount) }

        assertThat(events.map { Instant.ofEpochMilli(it.first).atZone(ZoneOffset.UTC).dayOfWeek })
            .containsExactly(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
                DayOfWeek.MONDAY,
            )
        assertThat(events.map { it.second })
            .containsExactly(
                BigDecimal("-1.00000000"),
                BigDecimal("-1.00000000"),
                BigDecimal("-3.00000000"),
                BigDecimal("-1.00000000"),
                BigDecimal("-1.00000000"),
                BigDecimal("-1.00000000"),
            )
        assertThat(book.dailyNet()[LocalDate.parse("2026-07-08")]).isEqualByComparingTo("-3")
    }

    @Test
    fun `leg opened at boundary starts accruing on next rollover`() {
        addLeg("long", Side.BUY, timestamp("2026-07-08T21:00:00Z"))
        val events = mutableListOf<BigDecimal>()
        val book = book()

        book.accrueBetween(
            timestamp("2026-07-08T20:59:59Z"),
            timestamp("2026-07-09T21:00:00Z"),
        ) { _, _, amount -> events.add(amount) }

        assertThat(events).containsExactly(BigDecimal("-1.00000000"))
    }

    @Test
    fun `quote currency swap is converted into account currency at boundary mark`() {
        val fxSymbol = "BACKTEST:USDJPY"
        val fxPositions = StrategyPositionTracker()
        val opened = timestamp("2026-07-06T20:00:00Z")
        fxPositions.addIndependentLeg(
            "s",
            PositionLeg(
                legId = "fx-long",
                symbol = fxSymbol,
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                entryPrice = BigDecimal("150"),
                openedAt = opened,
                role = LegRole.INDEPENDENT,
            ),
        )
        val meta =
            InstrumentMeta(
                qktSymbol = fxSymbol,
                contractSize = BigDecimal("100000"),
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.001"),
                digits = 3,
                tradeStopsLevelPoints = 0,
                swapLongPoints = BigDecimal("-1"),
            )
        val fxPrices = MarketPriceTracker().also { it.update(fxSymbol, BigDecimal("150")) }
        val book =
            SwapFinancingBook(
                instruments =
                    object : InstrumentRegistry {
                        override fun lookup(qktSymbol: String): InstrumentMeta? = meta.takeIf { qktSymbol == fxSymbol }
                    },
                strategyPositions = fxPositions,
                accounting = AccountingEngine(),
                prices = fxPrices,
                strategyIds = listOf("s"),
                symbols = listOf(fxSymbol),
            )
        val accrued = mutableListOf<BigDecimal>()

        book.accrueBetween(opened, timestamp("2026-07-06T21:00:00Z")) { _, _, amount ->
            accrued.add(amount)
        }

        // -1 point * .001 * 100,000 = -100 JPY; at 150 USDJPY this is -0.66666667 USD.
        assertThat(accrued).containsExactly(BigDecimal("-0.66666667"))
        assertThat(book.totalPaid()).isEqualByComparingTo("0.66666667")
    }
}
