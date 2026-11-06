package com.qkt.cli

import com.qkt.derivatives.options.chain.OptionChainFixture
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SymbolPattern
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** An event family's `CHAIN:` stream is its gateway account's to serve even where option chain analytics are routed. */
class MarketSourceFactoryEventChainTest {
    private val kalshi =
        object : MarketSource {
            override val name = "kalshi"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun tickDeliveryLagMsFor(symbol: String) = 42L
        }

    @Test
    fun `an event chain stream goes to its account and an option chain stream to chain analytics`(
        @TempDir dir: Path,
    ) {
        val routes = listOf(SymbolPattern { it.startsWith("CHAIN:KALSHI.") } to kalshi)
        val composite =
            MarketSourceFactory.composite(routes, source = "local", instruments = OptionChainFixture(dir).registry)(
                emptyList(),
            )

        assertThat(composite.tickDeliveryLagMsFor("CHAIN:KALSHI.KXS.overround.front")).isEqualTo(42L)
        assertThat(composite.tickDeliveryLagMsFor("CHAIN:KALSHI.BTC_USDC.atm_iv.30d")).isNotEqualTo(42L)
    }
}
