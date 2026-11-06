package com.qkt.research

import com.qkt.accounting.AccountingConfig
import com.qkt.backtest.SampleCadence
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.instrument.NoopInstrumentRegistry
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayWiringDefaultsTest {
    private val books = ReplayBooks(NoopInstrumentRegistry, AccountingConfig(), markTimestamp = { 0L })

    @Test
    fun `a flat book accrues no financing over a week`() {
        val financing = ReplayFinancing(NoopInstrumentRegistry, books, listOf("s"), listOf("EXNESS:XAUUSD"))
        val applied = mutableListOf<BigDecimal>()

        financing.accrueBetween(0L, 7 * 86_400_000L) { _, _, amount -> applied += amount }

        assertThat(applied).isEmpty()
        assertThat(financing.swapPaid()).isEqualByComparingTo("0")
    }

    @Test
    fun `observation and chain streams never form candles`() {
        val analytics =
            ReplayAnalytics(
                cadence = SampleCadence.TICK,
                bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator()),
                books = books,
                strategyIds = listOf("s"),
                strategyCount = 1,
                startingBalance = BigDecimal("1000"),
                symbols = listOf("BACKTEST:XAUUSD", "OPTIONS:DERIBIT.BTC_USDC", "CHAIN:DERIBIT.BTC_USDC.atm_iv.7d"),
                initialTimestamp = 0L,
                instruments = NoopInstrumentRegistry,
                bookRiskController = null,
            )

        assertThat(analytics.candleSymbols).containsExactly("BACKTEST:XAUUSD")
    }
}
