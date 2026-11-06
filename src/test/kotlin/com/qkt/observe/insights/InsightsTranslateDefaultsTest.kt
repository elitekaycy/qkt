package com.qkt.observe.insights

import com.qkt.accounting.ConvertedMoney
import com.qkt.accounting.MoneyAmount
import com.qkt.common.Side
import com.qkt.events.FillAccountedEvent
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateDefaultsTest {
    @Test
    fun `a strategy start without metadata carries only its id and time`() {
        val env = InsightsTranslate.strategyStarted("alpha", 5L)

        assertThat(env.type).isEqualTo("strategy.started")
        assertThat(env.payload).containsExactlyEntriesOf(mapOf("strategyId" to "alpha", "ts" to 5L))
    }

    @Test
    fun `a closed trade without a conversion leaves the currency fields empty`() {
        val trade = Trade("o-1", "XAUUSD", BigDecimal("4700"), BigDecimal("0.1"), Side.SELL, 9L)

        val env = InsightsTranslate.tradeClosed(trade, BigDecimal("12.5"), "alpha")

        assertThat(env.id).isEqualTo("tc-o-1-9")
        assertThat(env.payload)
            .containsEntry("realized", BigDecimal("12.5"))
            .containsEntry("accountCurrency", null)
            .containsEntry("costsAccount", null)
    }

    @Test
    fun `a closed trade converted without fx states amounts but no rate`() {
        val trade = Trade("o-2", "XAUUSD", BigDecimal("4700"), BigDecimal("0.1"), Side.SELL, 9L)
        val converted = ConvertedMoney(MoneyAmount(BigDecimal("13"), "usd"), MoneyAmount(BigDecimal("13"), "usd"), null)

        val env = InsightsTranslate.tradeClosed(trade, BigDecimal("12.5"), "", converted)

        assertThat(env.strategyId).isNull()
        assertThat(env.payload)
            .containsEntry("accountCurrency", "USD")
            .containsEntry("costsAccount", BigDecimal("0.5"))
            .containsEntry("fxRate", null)
            .containsEntry("fxSource", null)
    }

    @Test
    fun `an accounted fill without legs or positions leaves those fields empty`() {
        val zero = BigDecimal.ZERO
        val fill =
            FillAccountedEvent(
                orderId = "o-3",
                strategyId = "alpha",
                symbol = "XAUUSD",
                fillSliceId = "o-3:1",
                sourceFillSequenceId = 1L,
                cumulativeFilled = null,
                modeledCommissionAccount = zero,
                venueCostsAccount = zero,
                totalCostsAccount = zero,
                accountNativeRealized = zero,
                strategyNativeRealized = zero,
                nativeCurrency = "USD",
                grossAccountRealized = zero,
                grossStrategyAccountRealized = zero,
                accountCurrency = "USD",
                netAccountRealized = zero,
                netStrategyAccountRealized = zero,
                conversionRate = null,
                conversionTimestampMs = null,
                conversionSource = null,
                contractSize = null,
                accountPositionBefore = null,
                accountPositionAfter = null,
                strategyPositionBefore = null,
                strategyPositionAfter = null,
                reducedExposure = false,
                partial = false,
            )

        val env = InsightsTranslate.fromFillAccounted(fill)

        assertThat(env.type).isEqualTo("fill.accounted")
        assertThat(env.payload)
            .containsEntry("legAction", null)
            .containsEntry("accountPositionBefore", null)
            .containsEntry("strategyPositionAfter", null)
    }
}
