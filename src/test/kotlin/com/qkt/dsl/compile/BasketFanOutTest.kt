package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #457 Task B8 — a BUY/SELL on a basket alias fans out to one equal-notional plain-market
 * order per constituent. Brackets/TIF/stack and non-notional sizing on a basket are rejected
 * at compile time (basket orders are plain market in v1).
 */
class BasketFanOutTest {
    private val audKey = HubKey("EXNESS", "AUDUSD", "1h")
    private val nzdKey = HubKey("EXNESS", "NZDUSD", "1h")
    private val streams =
        mapOf(
            "aud" to audKey,
            "nzd" to nzdKey,
            "antipodean" to HubKey("BASKET", "ANTIPODEAN", "1h"),
        )

    private fun candle(
        key: HubKey,
        close: String,
    ): Candle =
        Candle(
            key.qktSymbol,
            BigDecimal(close),
            BigDecimal(close),
            BigDecimal(close),
            BigDecimal(close),
            BigDecimal.ZERO,
            0L,
            1L,
        )

    private fun registry(contractSize: String): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal(contractSize),
                    volumeStep = BigDecimal("0.01"),
                    volumeMin = BigDecimal("0.01"),
                    volumeMax = null,
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }

    private fun ec(
        audClose: String,
        nzdClose: String,
        contractSize: String,
    ): EvalContext {
        val hub = CandleHub()
        hub.register(audKey, retention = 5, strategyId = "test")
        hub.register(nzdKey, retention = 5, strategyId = "test")
        hub.publish(audKey, candle(audKey, audClose))
        hub.publish(nzdKey, candle(nzdKey, nzdClose))
        return EvalContext(
            candle = candle(audKey, audClose),
            streams = streams,
            lets = emptyMap(),
            strategyContext = testStrategyContext(instruments = registry(contractSize)),
            hub = hub,
        )
    }

    private fun fanOut() = ActionCompiler(ExprCompiler(), baskets = mapOf("antipodean" to listOf("aud", "nzd")))

    @Test
    fun `BUY basket fans out one equal-notional order per constituent`() {
        val action =
            fanOut().compile(Buy("antipodean", ActionOpts(sizing = SizeNotional(NumLit(BigDecimal("10000"))))))
        val signals = action(ec(audClose = "0.50", nzdClose = "1.00", contractSize = "100000"))
        // total 10000 -> 5000 per constituent.
        // qty_aud = 5000 / (0.50 * 100000) = 0.1; qty_nzd = 5000 / (1.00 * 100000) = 0.05
        assertThat(signals).hasSize(2)
        val aud = signals[0] as Signal.Buy
        val nzd = signals[1] as Signal.Buy
        assertThat(aud.symbol).isEqualTo("EXNESS:AUDUSD")
        assertThat(aud.size).isEqualByComparingTo("0.1")
        assertThat(nzd.symbol).isEqualTo("EXNESS:NZDUSD")
        assertThat(nzd.size).isEqualByComparingTo("0.05")
    }

    @Test
    fun `SELL basket fans out to the short side, same equal-notional quantities`() {
        val action =
            fanOut().compile(Sell("antipodean", ActionOpts(sizing = SizeNotional(NumLit(BigDecimal("10000"))))))
        val signals = action(ec(audClose = "0.50", nzdClose = "1.00", contractSize = "100000"))
        assertThat(signals).hasSize(2)
        assertThat(signals).allMatch { it is Signal.Sell }
        assertThat((signals[0] as Signal.Sell).size).isEqualByComparingTo("0.1")
        assertThat((signals[1] as Signal.Sell).size).isEqualByComparingTo("0.05")
    }

    @Test
    fun `non-notional sizing on a basket is rejected at compile`() {
        assertThatThrownBy {
            fanOut().compile(Buy("antipodean", ActionOpts(sizing = SizeQty(NumLit(BigDecimal("0.5"))))))
        }.hasMessageContaining("notional sizing")
    }

    @Test
    fun `missing sizing on a basket is rejected at compile`() {
        assertThatThrownBy {
            fanOut().compile(Buy("antipodean", ActionOpts()))
        }.hasMessageContaining("notional sizing")
    }

    @Test
    fun `a basket order carrying a BRACKET is rejected at compile`() {
        val src =
            """
            STRATEGY fan VERSION 1
            SYMBOLS
                aud = EXNESS:AUDUSD EVERY 1h
                nzd = EXNESS:NZDUSD EVERY 1h
                antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
            RULES
                WHEN aud.close > 0 THEN BUY antipodean SIZING 1000 USD BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }
            """.trimIndent()
        assertThatThrownBy {
            AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)
        }.hasMessageContaining("BRACKET")
    }
}
