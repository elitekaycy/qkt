package com.qkt.dsl.compile

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SizingCompilerTest {
    private val ec =
        EvalContext(
            candle =
                Candle(
                    "BACKTEST:BTCUSDT",
                    BigDecimal("100"),
                    BigDecimal("100"),
                    BigDecimal("100"),
                    BigDecimal("100"),
                    BigDecimal.ZERO,
                    0L,
                    1L,
                ),
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    private fun compiler() = SizingCompiler(ExprCompiler())

    @Test
    fun `SizeQty returns the expression`() {
        val s = compiler().compile(SizeQty(NumLit(BigDecimal("3"))), stopDistance = null, streamAlias = "btc")
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("3")
    }

    @Test
    fun `SizeNotional divides USD by entry price`() {
        val s = compiler().compile(SizeNotional(NumLit(BigDecimal("500"))), stopDistance = null, streamAlias = "btc")
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("5")
    }

    @Test
    fun `SizeRiskAbs computes quantity from amount and stop distance`() {
        // Default test registry uses contractSize = 1 so the math degenerates to amount / stopDistance.
        val s =
            compiler().compile(
                SizeRiskAbs(NumLit(BigDecimal("50"))),
                stopDistance = BigDecimal("5"),
                streamAlias = "btc",
            )
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("10")
    }

    @Test
    fun `SizeRiskAbs divides through contractSize from the registry`() {
        // Override the strategy context with a registry that reports contractSize = 100 for btc.
        val ecWithMeta =
            EvalContext(
                candle = ec.candle,
                streams = ec.streams,
                lets = ec.lets,
                strategyContext =
                    com.qkt.strategy.testStrategyContext(
                        instruments =
                            object : com.qkt.instrument.InstrumentRegistry {
                                override fun lookup(qktSymbol: String): com.qkt.instrument.InstrumentMeta =
                                    com.qkt.instrument.InstrumentMeta(
                                        qktSymbol = qktSymbol,
                                        contractSize = BigDecimal("100"),
                                        volumeStep = BigDecimal("0.01"),
                                        volumeMin = BigDecimal("0.01"),
                                        volumeMax = null,
                                        pointSize = BigDecimal("0.01"),
                                        digits = 2,
                                        tradeStopsLevelPoints = 0,
                                    )
                            },
                    ),
            )
        val s =
            compiler().compile(
                SizeRiskAbs(NumLit(BigDecimal("50"))),
                stopDistance = BigDecimal("5"),
                streamAlias = "btc",
            )
        // 50 / (5 * 100) = 0.1
        assertThat(s.evaluate(ecWithMeta, entryPrice = BigDecimal("100"))).isEqualByComparingTo("0.1")
    }

    @Test
    fun `SizeRiskAbs without stop distance errors at compile time`() {
        assertThatThrownBy {
            compiler().compile(
                SizeRiskAbs(NumLit(BigDecimal("50"))),
                stopDistance = null,
                streamAlias = "btc",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `SizePositionFull returns absolute quantity when flat`() {
        val s = compiler().compile(SizePositionFull("btc"), stopDistance = null, streamAlias = "btc")
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("0")
    }
}
