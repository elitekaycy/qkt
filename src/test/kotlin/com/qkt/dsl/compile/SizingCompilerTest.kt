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

    private fun ecWith(contractSize: BigDecimal): EvalContext =
        EvalContext(
            candle = ec.candle,
            streams = ec.streams,
            lets = ec.lets,
            strategyContext =
                com.qkt.strategy.testStrategyContext(
                    pnl =
                        object : com.qkt.pnl.StrategyPnLView {
                            override fun realized(): BigDecimal = BigDecimal.ZERO

                            override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                            override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                            override fun total(): BigDecimal = BigDecimal.ZERO

                            override fun equity(): BigDecimal = BigDecimal("10000")

                            override fun balance(): BigDecimal = BigDecimal("20000")
                        },
                    instruments =
                        object : com.qkt.instrument.InstrumentRegistry {
                            override fun lookup(qktSymbol: String): com.qkt.instrument.InstrumentMeta =
                                com.qkt.instrument.InstrumentMeta(
                                    qktSymbol = qktSymbol,
                                    contractSize = contractSize,
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

    private val ecWithContractSize100 = ecWith(BigDecimal("100"))

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
        val s =
            compiler().compile(
                SizeRiskAbs(NumLit(BigDecimal("50"))),
                stopDistance = BigDecimal("5"),
                streamAlias = "btc",
            )
        // 50 / (5 * 100) = 0.1
        assertThat(s.evaluate(ecWithContractSize100, entryPrice = BigDecimal("100"))).isEqualByComparingTo("0.1")
    }

    @Test
    fun `SizeNotional divides through contractSize so the result is lots`() {
        // XAUUSD-shaped: contractSize 100. $10,000 notional at $2,000/oz = 0.05 lots, not 5.
        val s = compiler().compile(SizeNotional(NumLit(BigDecimal("10000"))), stopDistance = null, streamAlias = "btc")
        assertThat(s.evaluate(ecWithContractSize100, entryPrice = BigDecimal("2000"))).isEqualByComparingTo("0.05")
    }

    @Test
    fun `SizePctEquity divides through contractSize so the result is lots`() {
        // 5% of $10,000 equity = $500 of exposure; at $2,000 x contractSize 100 = 0.0025 lots.
        val s =
            compiler().compile(
                com.qkt.dsl.ast
                    .SizePctEquity(NumLit(BigDecimal("0.05"))),
                stopDistance = null,
                streamAlias = "btc",
            )
        assertThat(s.evaluate(ecWithContractSize100, entryPrice = BigDecimal("2000"))).isEqualByComparingTo("0.0025")
    }

    @Test
    fun `SizePctBalance divides through contractSize so the result is lots`() {
        val s =
            compiler().compile(
                com.qkt.dsl.ast
                    .SizePctBalance(NumLit(BigDecimal("0.10"))),
                stopDistance = null,
                streamAlias = "btc",
            )
        // 10% of $20,000 balance = $2,000; at $2,000 x contractSize 100 = 0.01 lots.
        assertThat(s.evaluate(ecWithContractSize100, entryPrice = BigDecimal("2000"))).isEqualByComparingTo("0.01")
    }

    @Test
    fun `SizeNotional on an FX-shaped contractSize yields micro lots`() {
        // EURUSD-shaped: contractSize 100,000. $11,000 at 1.10 = 0.1 lots.
        val s = compiler().compile(SizeNotional(NumLit(BigDecimal("11000"))), stopDistance = null, streamAlias = "btc")
        assertThat(
            s.evaluate(ecWith(BigDecimal("100000")), entryPrice = BigDecimal("1.10")),
        ).isEqualByComparingTo("0.1")
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
