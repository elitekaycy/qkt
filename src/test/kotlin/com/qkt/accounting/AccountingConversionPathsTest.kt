package com.qkt.accounting

import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AccountingConversionPathsTest {
    private val usdJpy = "BACKTEST:USDJPY"

    private fun eur(amount: String) = VenueCost(CostKind.SWAP, MoneyAmount(BigDecimal(amount), "EUR"), 5L)

    @Test
    fun `a symbol converts when quoted in the account currency, from its own pair, or not at all`() {
        val engine = AccountingEngine()

        assertThat(engine.canConvertSymbol("BACKTEST:EURUSD")).isTrue()
        assertThat(engine.canConvertSymbol(usdJpy)).isTrue()
        assertThat(engine.canConvertSymbol("BACKTEST:EURJPY")).isFalse()
    }

    @Test
    fun `a fee coin converts as the account currency, from the fill's pair, from configuration, or not at all`() {
        val engine = AccountingEngine(AccountingConfig(symbols = mapOf("EURUSD" to "BACKTEST:EURUSD")))

        assertThat(engine.canConvertCoin("usd", "BACKTEST:XAUUSD")).isTrue()
        assertThat(engine.canConvertCoin("BTC", "BINANCE:BTCUSD")).isTrue()
        assertThat(engine.canConvertCoin("EUR", "BACKTEST:XAUUSD")).isTrue()
        assertThat(engine.canConvertCoin("BNB", "BACKTEST:XAUUSD")).isFalse()
    }

    @Test
    fun `an explicit currency wins over the suffix and an unknown symbol has none`() {
        val engine = AccountingEngine(currencyOf = { if (it == "CUSTOM:THING") "chf" else null })

        assertThat(engine.quoteCurrencyOf("CUSTOM:THING")).isEqualTo("CHF")
        assertThat(engine.quoteCurrencyOf("X")).isNull()
        assertThat(engine.pnlCurrencyFor("X")).isEqualTo("USD")
    }

    @Test
    fun `a pair's own price cannot convert without a positive reference price`() {
        val engine = AccountingEngine()

        for (reference in listOf(null, BigDecimal.ZERO)) {
            assertThatThrownBy { engine.convertPnl(usdJpy, BigDecimal("100"), 1L, reference) }
                .isInstanceOf(MissingFxRateException::class.java)
                .hasMessageContaining("missing FX conversion JPY->USD at 1 for $usdJpy")
        }
    }

    @Test
    fun `a configured direct pair converts at its market price`() {
        val prices = MarketPriceTracker().apply { update("BACKTEST:EURUSD", BigDecimal("1.1")) }
        val engine = AccountingEngine(AccountingConfig(symbols = mapOf("EURUSD" to "BACKTEST:EURUSD")), prices)

        val converted = engine.convertCost(eur("10"))

        assertThat(converted.account.amount).isEqualByComparingTo("11")
        assertThat(converted.conversion?.source).isEqualTo("market:BACKTEST:EURUSD")
    }

    @Test
    fun `a configured pair without a positive market price leaves the cost unconverted`() {
        val zero = MarketPriceTracker().apply { update("BACKTEST:EURUSD", BigDecimal.ZERO) }
        val config = AccountingConfig(symbols = mapOf("EURUSD" to "BACKTEST:EURUSD"))

        for (engine in listOf(AccountingEngine(config), AccountingEngine(config, zero))) {
            val converted = engine.convertCost(eur("10"))
            assertThat(converted.conversion).isNull()
            assertThat(converted.account.amount).isEqualByComparingTo("0")
        }
    }

    @Test
    fun `an unpriceable cost warns once per pair and names no symbol when it had none`() {
        val engine = AccountingEngine()

        engine.convertCost(eur("1"))
        engine.convertCost(eur("2"))

        val warning = engine.snapshot().warnings.single()
        assertThat(warning).startsWith("missing FX conversion EUR->USD at 5;")
        assertThat(warning).doesNotContain(" for ")
    }

    @Test
    fun `a missing rate under the warn policy books the native amount and records a warning`() {
        val engine = AccountingEngine(AccountingConfig(missingPolicy = FxMissingPolicy.WARN))

        val converted = engine.convertPnl("BACKTEST:EURJPY", BigDecimal("100"), 1L, BigDecimal("160"))

        assertThat(converted.conversion).isNull()
        assertThat(converted.account.amount).isEqualByComparingTo("100")
        assertThat(engine.snapshot().warnings.single()).contains("for BACKTEST:EURJPY")
    }
}
