package com.qkt.accounting.margin

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.accountingEngine
import com.qkt.instrument.EventContracts
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An event contract is fully collateralised: a side needs exactly what it can lose. */
class MarginModelEventTest {
    private val registry =
        EventContracts.registry(
            EventContracts.meta(),
            EventContracts.meta(symbol = "KALSHI:DOLLAR", terms = EventContracts.terms(payout = BigDecimal("100"))),
        )
    private val model = MarginModel(registry, accountingEngine(AccountingConfig(), MarketPriceTracker(), registry))
    private val yes = EventContracts.SYMBOL

    @Test
    fun `a YES position needs its premium, quantity times price`() {
        assertThat(model.initial(yes, BigDecimal("100"), BigDecimal("0.30"), 0L)).isEqualByComparingTo("30")
    }

    @Test
    fun `a NO position needs the rest of the payout, quantity times payout minus price`() {
        assertThat(model.initial(yes, BigDecimal("-100"), BigDecimal("0.30"), 0L)).isEqualByComparingTo("70")
        assertThat(model.initial("KALSHI:DOLLAR", BigDecimal("-2"), BigDecimal("30"), 0L)).isEqualByComparingTo("140")
    }

    @Test
    fun `an event contract opens on its premium but has no margin terms and no maintenance level`() {
        assertThat(model.requiresInitial(yes)).isTrue()
        assertThat(model.isEvent(yes)).isTrue()
        assertThat(model.hasTerms(yes)).isFalse()
        assertThat(model.maintenance(yes, BigDecimal("100"), BigDecimal("0.30"), 0L)).isEqualByComparingTo("0")
    }

    @Test
    fun `an unknown symbol is neither margined nor an event`() {
        assertThat(model.requiresInitial("EXNESS:XAUUSD")).isFalse()
        assertThat(model.isEvent("EXNESS:XAUUSD")).isFalse()
        assertThat(model.initial("EXNESS:XAUUSD", BigDecimal("1"), BigDecimal("2600"), 0L)).isEqualByComparingTo("0")
    }
}
