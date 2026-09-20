package com.qkt.observe.insights

import com.qkt.accounting.ConvertedMoney
import com.qkt.accounting.FxConversion
import com.qkt.accounting.MoneyAmount
import com.qkt.common.Side
import com.qkt.events.FillAccountedEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Trade
import com.qkt.positions.Position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateTradeTest {
    @Test
    fun `trade event maps to the contract trade payload`() {
        val e =
            TradeEvent(
                trade =
                    Trade(
                        orderId = "o1",
                        symbol = "XAUUSD",
                        price = BigDecimal("2350.5"),
                        quantity = BigDecimal("0.1"),
                        side = Side.BUY,
                        timestamp = 1718000000000L,
                    ),
                timestamp = 1718000000001L,
                sequenceId = 42L,
                strategyId = "latch",
            )
        val env = InsightsTranslate.fromTrade(e)
        assertThat(env.type).isEqualTo("trade")
        assertThat(env.strategyId).isEqualTo("latch")
        assertThat(env.seq).isEqualTo(42L)
        assertThat(env.id).isEqualTo("event-trade-latch-1718000000001-42")
        assertThat(env.payload["orderId"]).isEqualTo("o1")
        assertThat(env.payload["side"]).isEqualTo("BUY")
    }

    @Test
    fun `trade closed exposes net account pnl as canonical and gross reconciliation fields`() {
        val trade =
            Trade(
                orderId = "o-close",
                symbol = "EXNESS:USDJPY",
                price = BigDecimal("155.25"),
                quantity = BigDecimal("1000"),
                side = Side.SELL,
                timestamp = 1718000000000L,
            )
        val converted =
            ConvertedMoney(
                native = MoneyAmount(BigDecimal("1550.00"), "JPY"),
                account = MoneyAmount(BigDecimal("10.00"), "USD"),
                conversion =
                    FxConversion(
                        from = "JPY",
                        to = "USD",
                        rate = BigDecimal("0.0064516129"),
                        timestamp = 1717999999000L,
                        source = "market",
                    ),
            )

        val env =
            InsightsTranslate.tradeClosed(
                trade = trade,
                netAccountRealized = BigDecimal("9.25"),
                strategyId = "alpha",
                convertedRealized = converted,
            )

        assertThat(env.type).isEqualTo("trade.closed")
        assertThat(env.strategyId).isEqualTo("alpha")
        assertThat(env.payload).containsEntry("realized", BigDecimal("9.25"))
        assertThat(env.payload).containsEntry("netAccountRealized", BigDecimal("9.25"))
        assertThat(env.payload).containsEntry("grossAccountRealized", BigDecimal("10.00"))
        assertThat(env.payload).containsEntry("accountRealized", BigDecimal("10.00"))
        assertThat(env.payload).containsEntry("costsAccount", BigDecimal("0.75"))
        assertThat(env.payload).containsEntry("nativeRealized", BigDecimal("1550.00"))
        assertThat(env.payload).containsEntry("nativeCurrency", "JPY")
        assertThat(env.payload).containsEntry("accountCurrency", "USD")
        assertThat(env.payload).containsEntry("currency", "USD")
        assertThat(env.payload).containsEntry("fxRate", BigDecimal("0.0064516129"))
        assertThat(env.payload).containsEntry("fxRateTimestamp", 1717999999000L)
        assertThat(env.payload).containsEntry("fxSource", "market")
        assertThat(env.payload).containsEntry("pnlBasis", "net_account_after_costs")
        assertThat(env.payload).containsEntry("realizedAliasOf", "netAccountRealized")
        assertThat(env.payload).containsEntry("sideAttribution", "fill_side")

        val json = env.toJson("qkt-prod")
        assertThat(json).contains(""""realized":9.25""")
        assertThat(json).contains(""""netAccountRealized":9.25""")
        assertThat(json).contains(""""grossAccountRealized":10.00""")
        assertThat(json).contains(""""costsAccount":0.75""")
        assertThat(json).contains(""""accountCurrency":"USD"""")
        assertThat(json).doesNotContain(""""realized":"9.25"""")
    }

    @Test
    fun `accounted fill exposes costs realized pnl positions and fill correlation`() {
        val before =
            Position(
                symbol = "EXNESS:XAUUSD",
                quantity = BigDecimal("0.10"),
                avgEntryPrice = BigDecimal("2350.00"),
                openedAt = 1_717_999_000_000L,
            )
        val after = before.copy(quantity = BigDecimal("0.04"))
        val env =
            InsightsTranslate.fromFillAccounted(
                FillAccountedEvent(
                    orderId = "o-close",
                    strategyId = "alpha",
                    symbol = "EXNESS:XAUUSD",
                    fillSliceId = "o-close:91",
                    sourceFillSequenceId = 91L,
                    cumulativeFilled = BigDecimal("0.06"),
                    modeledCommissionAccount = BigDecimal("0.10"),
                    venueCostsAccount = BigDecimal("0.20"),
                    totalCostsAccount = BigDecimal("0.30"),
                    accountNativeRealized = BigDecimal("12.00"),
                    strategyNativeRealized = BigDecimal("7.20"),
                    nativeCurrency = "USD",
                    grossAccountRealized = BigDecimal("12.00"),
                    grossStrategyAccountRealized = BigDecimal("7.20"),
                    accountCurrency = "USD",
                    netAccountRealized = BigDecimal("11.70"),
                    netStrategyAccountRealized = BigDecimal("6.90"),
                    conversionRate = BigDecimal.ONE,
                    conversionTimestampMs = 1_718_000_000_000L,
                    conversionSource = "identity",
                    contractSize = BigDecimal("100"),
                    accountPositionBefore = before,
                    accountPositionAfter = after,
                    strategyPositionBefore = before,
                    strategyPositionAfter = after,
                    reducedExposure = true,
                    partial = true,
                    timestamp = 1_718_000_000_100L,
                    sequenceId = 92L,
                ),
            )

        assertThat(env.type).isEqualTo("fill.accounted")
        assertThat(env.strategyId).isEqualTo("alpha")
        assertThat(env.payload)
            .containsEntry("orderId", "o-close")
            .containsEntry("fillSliceId", "o-close:91")
            .containsEntry("sourceFillSequenceId", 91L)
            .containsEntry("cumulativeFilled", BigDecimal("0.06"))
            .containsEntry("modeledCommissionAccount", BigDecimal("0.10"))
            .containsEntry("venueCostsAccount", BigDecimal("0.20"))
            .containsEntry("totalCostsAccount", BigDecimal("0.30"))
            .containsEntry("grossAccountRealized", BigDecimal("12.00"))
            .containsEntry("netAccountRealized", BigDecimal("11.70"))
            .containsEntry("netStrategyAccountRealized", BigDecimal("6.90"))
            .containsEntry("kind", "EXECUTION")
            .containsKeys("legId", "legAction", "executedAt")
            .containsEntry("reducedExposure", true)
            .containsEntry("partial", true)
        assertThat(env.payload["accountPositionBefore"])
            .isEqualTo(
                mapOf(
                    "symbol" to "EXNESS:XAUUSD",
                    "quantity" to BigDecimal("0.10"),
                    "avgEntryPrice" to BigDecimal("2350.00"),
                    "openedAtMs" to 1_717_999_000_000L,
                ),
            )
        assertThat(env.payload["strategyPositionAfter"])
            .isEqualTo(
                mapOf(
                    "symbol" to "EXNESS:XAUUSD",
                    "quantity" to BigDecimal("0.04"),
                    "avgEntryPrice" to BigDecimal("2350.00"),
                    "openedAtMs" to 1_717_999_000_000L,
                ),
            )
        assertThat(env.toJson("qkt-prod"))
            .contains("\"sourceFillSequenceId\":91")
            .contains("\"totalCostsAccount\":0.30")
            .contains("\"accountPositionBefore\":{")
    }
}
