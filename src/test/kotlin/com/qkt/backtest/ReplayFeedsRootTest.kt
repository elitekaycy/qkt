package com.qkt.backtest

import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayFeedsRootTest {
    private val contract = "DERIBIT:BTC_USDC_26SEP26_84500_P"
    private val other = "DERIBIT:ETH_USDC_26SEP26_3000_C"

    /** A source whose root feed and contract feed both quote [contract] at the same instants. */
    private val source =
        object : MarketSource {
            override val name = "fake"
            override val capabilities = setOf(MarketSourceCapability.TICKS)

            override fun supports(symbol: String) = true

            override fun ticks(
                symbol: String,
                range: TimeRange,
            ): Sequence<Tick> =
                when (symbol) {
                    "OPTIONS:DERIBIT.BTC_USDC" ->
                        sequenceOf(
                            Tick(contract, BigDecimal.ONE, 1),
                            Tick(contract, BigDecimal.TEN, 2),
                        )
                    else -> sequenceOf(Tick(symbol, BigDecimal.ONE, 1), Tick(symbol, BigDecimal.TEN, 2))
                }
        }

    private fun drain(symbols: List<String>): List<Tick> {
        val feed =
            ReplayFeeds.merged(
                source,
                symbols,
                TimeRange(Instant.EPOCH, Instant.ofEpochMilli(10)),
                emptyMap(),
                null,
                false,
                { 0 },
            )
        return generateSequence { feed.next() }.toList()
    }

    @Test
    fun `a contract of a fed root is fed only by the root, other contracts keep their own feed`() {
        val ticks = drain(listOf("OPTIONS:DERIBIT.BTC_USDC", contract, other))

        assertThat(ticks.filter { it.symbol == contract }).hasSize(2)
        assertThat(ticks.filter { it.symbol == other }).hasSize(2)
    }
}
