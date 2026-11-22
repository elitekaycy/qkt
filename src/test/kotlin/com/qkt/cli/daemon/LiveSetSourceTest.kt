package com.qkt.cli.daemon

import com.qkt.dsl.sets.DynamicSetFixtures
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.util.concurrent.CountDownLatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A live session's listing poller lives exactly as long as the session's feed. */
class LiveSetSourceTest {
    private val quiet =
        object : MarketSource {
            override val name = "Quiet"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    private val closed = CountDownLatch(1)

                    override fun next(): Tick? = null.also { closed.await() }

                    override fun close() = closed.countDown()
                }
        }

    private val catalog =
        object : SetCatalog {
            override val source = "the venue listing"
            override val survivorsOnly = false

            override fun venue(
                alias: String,
                spec: SymbolSetSpec.Filtered,
            ) = VenueCatalog(DynamicSetFixtures.rows)
        }

    private fun pollers(): Int = Thread.getAllStackTraces().keys.count { it.name == "live-set-poller" && it.isAlive }

    private fun awaitPollers(count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (pollers() != count && System.nanoTime() < deadline) Thread.sleep(5)
        assertThat(pollers()).isEqualTo(count)
    }

    @Test
    fun `the poller starts when the session opens its feed and stops when that feed closes`() {
        val host = DynamicSetFixtures.plan(minutes = 1).strategy(emptyMap(), live = true) as DynamicSetHost
        val before = pollers()
        val source = liveSetSource(host, catalog, quiet)
        assertThat(pollers()).isEqualTo(before)
        val first = source.liveTicks(listOf("BACKTEST:A"))
        awaitPollers(before + 1)
        val second = source.liveTicks(listOf("BACKTEST:A"))
        awaitPollers(before + 1)
        second.close()
        awaitPollers(before)
        first.close()
    }
}
