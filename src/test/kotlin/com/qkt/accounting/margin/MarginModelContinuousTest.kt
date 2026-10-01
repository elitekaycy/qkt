package com.qkt.accounting.margin

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.accountingEngine
import com.qkt.broker.continuous.ContinuousFixture
import com.qkt.broker.continuous.ContinuousFixture.Companion.ms
import com.qkt.instrument.MarginBasis
import com.qkt.instrument.MarginTerms
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** On 2024-09-20 the fixture's front contract (December) trades 800 above the adjusted series. */
class MarginModelContinuousTest {
    private val registry =
        ContinuousFixture(margin = MarginTerms(BigDecimal("0.05"), BigDecimal("0.025"), MarginBasis.NOTIONAL)).registry
    private val model = MarginModel(registry, accountingEngine(AccountingConfig(), MarketPriceTracker(), registry))

    @Test
    fun `a continuous stream is margined on the contract it holds, not on the adjusted series`() {
        val margin =
            model.initial(
                "BINANCE_UM:BTCUSDT@front",
                BigDecimal("0.5"),
                BigDecimal("63000"),
                ms("2024-09-20T00:00:00Z"),
            )

        // 0.5 x 63800 x 5%
        assertThat(margin).isEqualByComparingTo("1595")
    }
}
