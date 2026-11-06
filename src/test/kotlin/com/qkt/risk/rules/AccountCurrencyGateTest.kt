package com.qkt.risk.rules

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.AccountingEngine
import com.qkt.accounting.FxMissingPolicy
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.book.BookLimits
import com.qkt.risk.book.BookRiskConfig
import com.qkt.risk.book.BookRiskController
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Notional and exposure caps refuse an order they cannot value in the account currency. */
class AccountCurrencyGateTest {
    private val prices = MarketPriceTracker().apply { update("X", BigDecimal("100")) }
    private val positions = StrategyPositionTracker().account
    private val order = OrderRequest.Market("o", "X", Side.BUY, BigDecimal("1"), TimeInForce.GTC, 1L)

    private fun eurQuoted(policy: FxMissingPolicy) =
        AccountingEngine(AccountingConfig(missingPolicy = policy), currencyOf = { "EUR" })

    private fun book() =
        BookRiskController(
            config = BookRiskConfig(limits = BookLimits(maxGrossExposure = BigDecimal("3"))),
            capital = BigDecimal("10000"),
        )

    @Test
    fun `the order notional cap refuses when the fx rate is missing under the fail policy`() {
        val rule = MaxOrderNotional(BigDecimal("1000000"), prices, accounting = eurQuoted(FxMissingPolicy.FAIL))

        val reason = (rule.evaluate(order, positions) as Decision.Reject).reason

        assertThat(reason).startsWith("cannot compute account-currency notional for X: missing FX conversion EUR->USD")
    }

    @Test
    fun `the order notional cap refuses an unconverted notional under the warn policy`() {
        val rule = MaxOrderNotional(BigDecimal("1000000"), prices, accounting = eurQuoted(FxMissingPolicy.WARN))

        val reason = (rule.evaluate(order, positions) as Decision.Reject).reason

        assertThat(reason).isEqualTo("cannot compute account-currency notional for X: missing FX conversion EUR->USD")
    }

    @Test
    fun `the book exposure cap refuses when the fx rate is missing under the fail policy`() {
        val rule = BookExposureLimit(book(), prices, NoopInstrumentRegistry, eurQuoted(FxMissingPolicy.FAIL))

        val reason = (rule.evaluate(order, positions) as Decision.Reject).reason

        assertThat(reason).startsWith("cannot compute account-currency exposure for X: missing FX conversion EUR->USD")
    }

    @Test
    fun `the book exposure cap refuses an unconverted exposure under the warn policy`() {
        val rule = BookExposureLimit(book(), prices, NoopInstrumentRegistry, eurQuoted(FxMissingPolicy.WARN))

        val reason = (rule.evaluate(order, positions) as Decision.Reject).reason

        assertThat(reason).isEqualTo("cannot compute account-currency exposure for X: missing FX conversion EUR->USD")
    }

    @Test
    fun `the missing-rate policy reads warn and refuses an unknown word`() {
        assertThat(FxMissingPolicy.fromConfig(" Warn ")).isEqualTo(FxMissingPolicy.WARN)
        assertThat(FxMissingPolicy.fromConfig(null)).isEqualTo(FxMissingPolicy.FAIL)
        assertThat(FxMissingPolicy.fromConfig("strict")).isEqualTo(FxMissingPolicy.FAIL)
        assertThatThrownBy { FxMissingPolicy.fromConfig("lenient") }
            .hasMessage("unknown fx_conversion.missing_policy 'lenient' (valid: warn, fail)")
    }
}
