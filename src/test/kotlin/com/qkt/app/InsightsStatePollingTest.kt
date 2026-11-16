package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.observe.insights.BrokerStatePollerFixture
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.SharedDealFetch
import com.qkt.observe.insights.TicketAttribution
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The venue poller a session starts samples its strategies before an engine handle exists. */
class InsightsStatePollingTest : BrokerStatePollerFixture() {
    private val clock = FixedClock(1_700_000_000_000L)
    private val strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }
    private val strategies = listOf("alpha" to strategy)
    private val source =
        object : MarketSource {
            override val name = "fake"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true
        }

    @Test
    fun `a poller started before the engine handle exists polls the venue and announces the roster`() {
        val insights =
            InsightsLifecycle(
                sink,
                setOf(InsightsEventFamily.STATE),
                emptyMap(),
                strategies,
                source,
                listOf("X"),
                clock,
            )
        val polling =
            InsightsStatePolling(
                strategies,
                sink,
                setOf(InsightsEventFamily.STATE),
                insights,
                TicketAttribution(),
                { listOf("beta", "alpha") },
                60_000L,
                SharedDealFetch(),
                1L,
                clock,
            )
        val prices = MarketPriceTracker()

        val poller =
            polling.start(
                listOf(FakeBroker()),
                AtomicReference(null),
                StrategyPnL(StrategyPositionTracker(), prices),
            ) { _, _, _ -> null }
        poller!!.drainAndClose()

        assertThat(collectBodies("state.account")).contains("state.account")
    }
}
