package com.qkt.accounting

import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AccountingEngineTest {
    @Test
    fun `USDJPY converts JPY pnl into USD from the fill price`() {
        val engine = AccountingEngine()

        val converted =
            engine.convertPnl(
                symbol = "BACKTEST:USDJPY",
                nativeAmount = BigDecimal("100000"),
                timestamp = 2_000L,
                referencePrice = BigDecimal("151"),
            )

        assertThat(converted.native.amount).isEqualByComparingTo("100000.00000000")
        assertThat(converted.native.normalizedCurrency).isEqualTo("JPY")
        assertThat(converted.account.amount).isEqualByComparingTo("662.25165563")
        assertThat(converted.account.normalizedCurrency).isEqualTo("USD")
        assertThat(converted.conversion!!.rate).isEqualByComparingTo("0.006622516556291391")
        assertThat(converted.conversion!!.timestamp).isEqualTo(2_000L)
        assertThat(converted.conversion!!.source).isEqualTo("context:BACKTEST:USDJPY")
    }

    @Test
    fun `configured inverse market symbol converts cross pnl`() {
        val engine =
            AccountingEngine(
                config =
                    AccountingConfig(
                        symbols = mapOf("USDJPY" to "BACKTEST:USDJPY"),
                    ),
                prices = fixedPrices("BACKTEST:USDJPY" to BigDecimal("151")),
            )

        val converted =
            engine.convertPnl(
                symbol = "BACKTEST:EURJPY",
                nativeAmount = BigDecimal("100000"),
                timestamp = 2_000L,
                referencePrice = BigDecimal("160"),
            )

        assertThat(engine.canConvertSymbol("BACKTEST:EURJPY")).isTrue()
        assertThat(converted.account.amount).isEqualByComparingTo("662.25165563")
        assertThat(converted.conversion!!.source).isEqualTo("market:BACKTEST:USDJPY")
    }

    @Test
    fun `missing conversion fails when policy is fail`() {
        val engine =
            AccountingEngine(
                config = AccountingConfig(missingPolicy = FxMissingPolicy.FAIL),
            )

        assertThatThrownBy {
            engine.convertPnl(
                symbol = "BACKTEST:EURJPY",
                nativeAmount = BigDecimal("100000"),
                timestamp = 2_000L,
                referencePrice = BigDecimal("160"),
            )
        }.isInstanceOf(MissingFxRateException::class.java)
            .hasMessageContaining("missing FX conversion JPY->USD")
    }

    @Test
    fun `typed costs convert at their own timestamp`() {
        val engine = AccountingEngine()

        val converted =
            engine.convertCost(
                cost =
                    VenueCost(
                        kind = CostKind.COMMISSION,
                        amount = MoneyAmount(BigDecimal("151"), "JPY"),
                        timestamp = 3_000L,
                    ),
                contextSymbol = "BACKTEST:USDJPY",
                referencePrice = BigDecimal("151"),
            )

        assertThat(converted.account.amount).isEqualByComparingTo("1.00000000")
        assertThat(converted.conversion!!.timestamp).isEqualTo(3_000L)
    }

    @Test
    fun `notional converts from quote currency into account currency`() {
        val engine = AccountingEngine()

        val converted =
            engine.convertNotional(
                symbol = "BACKTEST:USDJPY",
                nativeNotional = BigDecimal("15000000"),
                timestamp = 4_000L,
                referencePrice = BigDecimal("150"),
            )

        assertThat(converted.native.amount).isEqualByComparingTo("15000000.00000000")
        assertThat(converted.native.normalizedCurrency).isEqualTo("JPY")
        assertThat(converted.account.amount).isEqualByComparingTo("100000.00000000")
        assertThat(converted.account.normalizedCurrency).isEqualTo("USD")
        assertThat(converted.conversion!!.source).isEqualTo("context:BACKTEST:USDJPY")
    }

    private fun fixedPrices(vararg entries: Pair<String, BigDecimal>): MarketPriceProvider =
        object : MarketPriceProvider {
            private val prices = entries.toMap()

            override fun lastPrice(symbol: String): BigDecimal? = prices[symbol]
        }
}
