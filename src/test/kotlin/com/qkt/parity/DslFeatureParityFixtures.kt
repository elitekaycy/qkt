package com.qkt.parity

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat

/** Runs a DSL strategy over a one-minute price tape and asserts backtest and live end in the same full state. */
internal object DslFeatureParityFixtures {
    val symbol = "BACKTEST:BTCUSDT"
    val initialTs = 1_700_000_000_000L

    fun assertParity(
        strategyId: String,
        source: String,
        prices: List<String>,
    ): DslParityHarness.Result {
        val result = DslParityHarness.run(strategyId, source, tape(prices))
        assertThat(result.backtest.trades).isNotEmpty
        assertThat(result.live).isEqualTo(result.backtest)
        return result
    }

    fun tape(prices: List<String>): List<Tick> =
        prices.mapIndexed { index, price ->
            Tick(symbol, Money.of(price), initialTs + index * 60_000L)
        }
}
