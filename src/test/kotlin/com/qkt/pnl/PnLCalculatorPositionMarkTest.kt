package com.qkt.pnl

import com.qkt.accounting.AccountCurrency
import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.AccountingEngine
import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [PnLCalculator.markOf] values a position it does not hold by the rules [PnLCalculator.unrealizedFor] uses. */
class PnLCalculatorPositionMarkTest {
    private val symbol = "PAPER_DERIBIT_B:BTC_USDC_PERPETUAL"
    private val prices = MarketPriceTracker()
    private val instruments =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                InstrumentMeta(qktSymbol, TEN, STEP, STEP, null, BigDecimal("0.5"), 1, 0).takeIf { qktSymbol == symbol }
        }
    private val pnl = PnLCalculator(StrategyPositionTracker().account, prices, instruments)

    @Test
    fun `a symbol with no quote yet has no mark, never a zero one`() {
        assertThat(pnl.markOf(symbol, BigDecimal("0.001"), BigDecimal("79994.8"))).isNull()
    }

    @Test
    fun `a long is marked at the bid and valued with the contract size`() {
        prices.update(Tick(symbol, BigDecimal("80100.5"), 1L, bid = BigDecimal("80100.0"), ask = BigDecimal("80101.0")))

        val mark = pnl.markOf(symbol, BigDecimal("0.001"), BigDecimal("79994.8"))!!

        assertThat(mark.mark).isEqualByComparingTo("80100.0")
        // (80100.0 - 79994.8) x 0.001 x 10
        assertThat(mark.unrealized).isEqualByComparingTo("1.052")
    }

    @Test
    fun `a short is marked at the ask`() {
        prices.update(Tick(symbol, BigDecimal("80100.5"), 1L, bid = BigDecimal("80100.0"), ask = BigDecimal("80101.0")))

        val mark = pnl.markOf(symbol, BigDecimal("-0.002"), BigDecimal("80201.0"))!!

        assertThat(mark.mark).isEqualByComparingTo("80101.0")
        // (80101.0 - 80201.0) x -0.002 x 10
        assertThat(mark.unrealized).isEqualByComparingTo("2")
    }

    @Test
    fun `a mark is valued the same as a held position of the same size`() {
        val ledger = StrategyPositionTracker()
        val held = PnLCalculator(ledger.account, prices, instruments)
        ledger.apply("a", Trade("O1", symbol, BigDecimal("79994.8"), BigDecimal("0.003"), Side.BUY, 1L))
        prices.update(Tick(symbol, BigDecimal("80000"), 2L, bid = BigDecimal("79990.1"), ask = BigDecimal("80009.9")))

        assertThat(held.markOf(symbol, BigDecimal("0.003"), BigDecimal("79994.8"))!!.unrealized)
            .isEqualByComparingTo(held.unrealizedFor(symbol))
    }

    @Test
    fun `a quote currency with no rate to the account keeps the mark and states no profit`() {
        val accounting = AccountingEngine(AccountingConfig(AccountCurrency("USD")), currencyOf = { "EUR" })
        val eur = PnLCalculator(StrategyPositionTracker().account, prices, instruments, accounting)
        prices.update(Tick(symbol, BigDecimal("80100.5"), 1L, bid = BigDecimal("80100.0"), ask = BigDecimal("80101.0")))

        val mark = eur.markOf(symbol, BigDecimal("0.001"), BigDecimal("79994.8"))!!

        assertThat(mark.mark).isEqualByComparingTo("80100.0")
        assertThat(mark.unrealized).isNull()
    }

    private companion object {
        val TEN = BigDecimal("10")
        val STEP = BigDecimal("0.001")
    }
}
