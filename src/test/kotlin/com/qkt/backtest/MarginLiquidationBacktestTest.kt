package com.qkt.backtest

import com.qkt.common.Side
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.MarginBasis
import com.qkt.instrument.MarginTerms
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1307: a backtest liquidates a futures account whose equity falls below maintenance margin intraday,
 * as the venue would, instead of riding the drawdown out.
 */
class MarginLiquidationBacktestTest {
    private val symbol = "BINANCE_UM:BTCUSDT"
    private val start = Instant.parse("2024-09-20T00:00:00Z").toEpochMilli()

    private fun registry(margin: MarginTerms?) =
        ContractCatalogRegistry(
            listOf(
                FuturesRoot(
                    root = symbol,
                    currency = "USD",
                    multiplier = BigDecimal.ONE,
                    tickSize = BigDecimal("0.1"),
                    volumeStep = BigDecimal("0.001"),
                    volumeMin = BigDecimal("0.001"),
                    volumeMax = BigDecimal("100"),
                    calendar = null,
                    exchangeFeePerContract = BigDecimal.ZERO,
                    takerFeeRate = BigDecimal("0.0005"),
                    margin = margin,
                    perpetual = "BTCUSDT",
                ),
            ),
            mapOf(symbol to ContractCatalog(symbol, emptyList())),
        )

    /** Buys [quantity] contracts on the first tick, then [onLater] on every later tick. */
    private fun buyOnce(
        quantity: String,
        onLater: (Int) -> Signal? = { null },
    ): Strategy =
        object : Strategy {
            private var step = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                val n = step++
                if (n == 0) emit(Signal.Buy(symbol, BigDecimal(quantity))) else onLater(n)?.let(emit)
            }
        }

    private fun run(
        prices: List<String>,
        margin: MarginTerms? = TEN_FIVE,
        strategy: Strategy = buyOnce("1"),
    ) = Backtest(
        strategies = listOf("s" to strategy),
        ticks = prices.mapIndexed { i, p -> Tick(symbol, BigDecimal(p), start + i * 60_000L) },
        initialTimestamp = start,
        startingBalance = BigDecimal("10000"),
        instruments = registry(margin),
        tradedSymbols = listOf(symbol),
    ).run()

    @Test
    fun `an intraday fall below maintenance liquidates the position at that tick's price`() {
        val result = run(listOf("60000", "52000", "60000"))

        val liquidation = result.liquidations.single()
        assertThat(liquidation.atMs).isEqualTo(start + 60_000L)
        assertThat(liquidation.side).isEqualTo(Side.SELL)
        assertThat(liquidation.quantity).isEqualByComparingTo("1")
        assertThat(liquidation.price).isEqualByComparingTo("52000")
        assertThat(liquidation.fee).isEqualByComparingTo("26")
        assertThat(liquidation.equity).isEqualByComparingTo("1970")
        assertThat(liquidation.maintenance).isEqualByComparingTo("2600")
        // -8,000 on the move, 30 entry fee, 26 liquidation fee: the recovery to 60,000 comes too late.
        assertThat(result.global.realizedTotal).isEqualByComparingTo("-8056")
        assertThat(result.finalPositions[symbol]?.quantity ?: BigDecimal.ZERO).isEqualByComparingTo("0")
    }

    @Test
    fun `the liquidation is booked as the closing trade`() {
        val close = run(listOf("60000", "52000", "60000")).trades.last().trade

        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.quantity).isEqualByComparingTo("1")
        assertThat(close.price).isEqualByComparingTo("52000")
    }

    @Test
    fun `a drawdown that stays above maintenance is held`() {
        val result = run(listOf("60000", "54000", "60000"))

        assertThat(result.liquidations).isEmpty()
        assertThat(result.finalPositions[symbol]?.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `a root without margin terms is never liquidated`() {
        val result = run(listOf("60000", "52000", "60000"), margin = null)

        assertThat(result.liquidations).isEmpty()
        assertThat(result.finalPositions[symbol]?.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `an account can trade again once a liquidation has restored it`() {
        val strategy = buyOnce("1") { n -> if (n == 2) Signal.Buy(symbol, BigDecimal("0.01")) else null }

        val result = run(listOf("60000", "52000", "60000"), strategy = strategy)

        assertThat(result.liquidations).hasSize(1)
        assertThat(result.finalPositions[symbol]?.quantity).isEqualByComparingTo("0.01")
    }

    private companion object {
        val TEN_FIVE = MarginTerms(BigDecimal("0.10"), BigDecimal("0.05"), MarginBasis.NOTIONAL)
    }
}
