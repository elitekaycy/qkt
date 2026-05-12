package com.qkt.broker.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5OrderTranslatorTest {
    private val profile = MT5DefaultProfiles.exness
    private val translator =
        MT5OrderTranslator(
            profile = profile,
            symbol = MT5Symbol(profile.symbolPolicy),
        )

    private fun single(req: OrderRequest): MT5OrderRequest =
        (translator.translate(req) as MT5Translation.Single).request

    private fun marketReq(side: Side) =
        OrderRequest.Market(
            id = "ord-1",
            symbol = "EURUSD",
            side = side,
            quantity = BigDecimal("0.1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s1",
        )

    @Test
    fun `BUY market translates to BUY type with suffixed symbol`() {
        val mt5 = single(marketReq(Side.BUY))
        assertThat(mt5.symbol).isEqualTo("EURUSDm")
        assertThat(mt5.type).isEqualTo("BUY")
        assertThat(mt5.volume).isEqualByComparingTo("0.1")
        assertThat(mt5.sl).isNull()
        assertThat(mt5.tp).isNull()
        assertThat(mt5.magic).isEqualTo(10001)
        assertThat(mt5.comment).isEqualTo("ord-1")
    }

    @Test
    fun `SELL market translates to SELL type`() {
        assertThat(single(marketReq(Side.SELL)).type).isEqualTo("SELL")
    }

    @Test
    fun `Bracket with sl and tp translates with both fields`() {
        val entry = marketReq(Side.BUY)
        val bracket =
            OrderRequest.Bracket(
                id = "br-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entry = entry,
                takeProfit = BigDecimal("1.1500"),
                stopLoss = BigDecimal("1.0500"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val mt5 = single(bracket)
        assertThat(mt5.type).isEqualTo("BUY")
        assertThat(mt5.symbol).isEqualTo("EURUSDm")
        assertThat(mt5.sl).isEqualByComparingTo("1.0500")
        assertThat(mt5.tp).isEqualByComparingTo("1.1500")
        assertThat(mt5.comment).isEqualTo("br-1")
    }

    @Test
    fun `BUY Stop translates to BUY_STOP with price`() {
        val req =
            OrderRequest.Stop(
                id = "s-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val mt5 = single(req)
        assertThat(mt5.type).isEqualTo("BUY_STOP")
        assertThat(mt5.price).isEqualByComparingTo("1.1050")
        assertThat(mt5.symbol).isEqualTo("EURUSDm")
    }

    @Test
    fun `SELL Stop translates to SELL_STOP`() {
        val req =
            OrderRequest.Stop(
                id = "s-2",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.0950"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        assertThat(single(req).type).isEqualTo("SELL_STOP")
    }

    @Test
    fun `BUY Limit translates to BUY_LIMIT with price`() {
        val req =
            OrderRequest.Limit(
                id = "l-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                limitPrice = BigDecimal("1.0950"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        val mt5 = single(req)
        assertThat(mt5.type).isEqualTo("BUY_LIMIT")
        assertThat(mt5.price).isEqualByComparingTo("1.0950")
    }

    @Test
    fun `SELL Limit translates to SELL_LIMIT`() {
        val req =
            OrderRequest.Limit(
                id = "l-2",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                limitPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        assertThat(single(req).type).isEqualTo("SELL_LIMIT")
    }

    @Test
    fun `BUY StopLimit translates with both prices`() {
        val req =
            OrderRequest.StopLimit(
                id = "sl-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                limitPrice = BigDecimal("1.1060"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        val mt5 = single(req)
        assertThat(mt5.type).isEqualTo("BUY_STOP_LIMIT")
        assertThat(mt5.price).isEqualByComparingTo("1.1050")
        assertThat(mt5.stopLimit).isEqualByComparingTo("1.1060")
    }

    @Test
    fun `TrailingStop ABSOLUTE translates with sl_distance in MT5 points`() {
        val profileWithOverride =
            profile.copy(
                instrumentOverrides =
                    mapOf(
                        "EURUSD" to
                            InstrumentSpec(
                                minVolume = BigDecimal("0.01"),
                                volumeStep = BigDecimal("0.01"),
                                pointSize = BigDecimal("0.00001"),
                                digits = 5,
                                tradeStopsLevelPoints = 10,
                            ),
                    ),
            )
        val translator = MT5OrderTranslator(profileWithOverride, MT5Symbol(profileWithOverride.symbolPolicy))
        val req =
            OrderRequest.TrailingStop(
                id = "tr-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                trailAmount = BigDecimal("0.00200"), // 200 points at 0.00001/point
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        val mt5 = (translator.translate(req) as MT5Translation.Single).request
        assertThat(mt5.type).isEqualTo("BUY")
        assertThat(mt5.slDistance).isEqualTo(200L)
    }

    @Test
    fun `TrailingStop PERCENT without priceTracker rejects with actionable message`() {
        val req =
            OrderRequest.TrailingStop(
                id = "tr-pct-none",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                trailAmount = BigDecimal("1.5"),
                trailMode = TrailMode.PERCENT,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        assertThatThrownBy { translator.translate(req) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("MarketPriceProvider")
    }

    @Test
    fun `TrailingStop PERCENT with priceTracker computes slDistance from current price`() {
        val tracker =
            object : com.qkt.marketdata.MarketPriceProvider {
                override fun lastPrice(symbol: String): BigDecimal? =
                    if (symbol == "EURUSD") BigDecimal("1.10000") else null
            }
        val profileWithOverride =
            profile.copy(
                instrumentOverrides =
                    mapOf(
                        "EURUSD" to
                            InstrumentSpec(
                                minVolume = BigDecimal("0.01"),
                                volumeStep = BigDecimal("0.01"),
                                pointSize = BigDecimal("0.00001"),
                                digits = 5,
                                tradeStopsLevelPoints = 10,
                            ),
                    ),
            )
        val translator =
            MT5OrderTranslator(
                profileWithOverride,
                MT5Symbol(profileWithOverride.symbolPolicy),
                tracker,
            )
        val req =
            OrderRequest.TrailingStop(
                id = "tr-pct",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                // 0.5% of 1.10000 = 0.00550 = 550 points at 0.00001/point
                trailAmount = BigDecimal("0.5"),
                trailMode = TrailMode.PERCENT,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        val mt5 = (translator.translate(req) as MT5Translation.Single).request
        assertThat(mt5.slDistance).isEqualTo(550L)
    }

    @Test
    fun `TrailingStop PERCENT with priceTracker but no lastPrice fails actionably`() {
        val tracker =
            object : com.qkt.marketdata.MarketPriceProvider {
                override fun lastPrice(symbol: String): BigDecimal? = null
            }
        val profileWithOverride =
            profile.copy(
                instrumentOverrides =
                    mapOf(
                        "EURUSD" to
                            InstrumentSpec(
                                minVolume = BigDecimal("0.01"),
                                volumeStep = BigDecimal("0.01"),
                                pointSize = BigDecimal("0.00001"),
                                digits = 5,
                                tradeStopsLevelPoints = 10,
                            ),
                    ),
            )
        val translator =
            MT5OrderTranslator(
                profileWithOverride,
                MT5Symbol(profileWithOverride.symbolPolicy),
                tracker,
            )
        val req =
            OrderRequest.TrailingStop(
                id = "tr-pct-nolast",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                trailAmount = BigDecimal("0.5"),
                trailMode = TrailMode.PERCENT,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        assertThatThrownBy { translator.translate(req) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("tick stream")
    }

    @Test
    fun `TrailingStop without instrument override fails with actionable message`() {
        val req =
            OrderRequest.TrailingStop(
                id = "tr-3",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                trailAmount = BigDecimal("1.0"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        assertThatThrownBy { translator.translate(req) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("instrumentOverrides")
    }

    @Test
    fun `StandaloneOCO with two Stop legs translates to Composite with shared tag`() {
        val buyStop =
            OrderRequest.Stop(
                id = "buy",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        val sellStop =
            OrderRequest.Stop(
                id = "sell",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.0950"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                leg1 = buyStop,
                leg2 = sellStop,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        val out = translator.translate(oco) as MT5Translation.Composite
        assertThat(out.groupId).isEqualTo("oco-1")
        assertThat(out.requests).hasSize(2)
        assertThat(out.requests[0].type).isEqualTo("BUY_STOP")
        assertThat(out.requests[1].type).isEqualTo("SELL_STOP")
        assertThat(out.requests[0].comment).startsWith("oco:oco-1/")
        assertThat(out.requests[1].comment).startsWith("oco:oco-1/")
        assertThat(out.requests[0].comment).contains("buy")
        assertThat(out.requests[1].comment).contains("sell")
    }
}
