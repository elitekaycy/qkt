package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.marketdata.FeedFault
import com.qkt.marketdata.MarketDataGateConfig
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A stale quote pages the operator only when it matters: at once when the session holds a position or
 * working orders on the symbol, otherwise once the venue has stayed silent in session for the quiet
 * threshold. Clock skew and outlier runs page at once. Measured on hg20k copper: the broker's own tick
 * history goes quiet for minutes while nothing is open, and every such page was noise.
 */
class MarketDataHealthAlertsTest {
    private val pages = mutableListOf<String>()

    private val notifier =
        object : Notifier {
            override fun notify(event: NotificationEvent) {
                if (event is NotificationEvent.StrategyError) pages.add(event.message)
            }

            override fun close() {}
        }

    private val strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private val source =
        object : MarketSource {
            override val name = "mt5"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true
        }

    private var exposure = SymbolExposure.NONE

    private fun alerts(quietAlertAfterMs: Long = 600_000L): MarketDataHealthAlerts {
        val clock = FixedClock(1L)
        val strategies = listOf("s1" to strategy)
        val sessionNotifier =
            SessionNotifier(notifier, setOf(NotifyEventKind.STRATEGY_ERROR), null, strategies, clock)
        val insights = InsightsLifecycle(null, emptySet(), emptyMap(), strategies, source, listOf("X"), clock)
        val config = MarketDataGateConfig.DEFAULT.copy(quietAlertAfterMs = quietAlertAfterMs)
        return MarketDataHealthAlerts(strategies, sessionNotifier, insights, config).also {
            it.exposureFor = { exposure }
        }
    }

    private val stale = "quote age 61000ms exceeds 60000ms threshold"

    @Test
    fun `a stale quote with nothing open holds the page`() {
        val alerts = alerts()
        alerts.onUnhealthy("X", stale, FeedFault.STALE)
        alerts.onStillStale("X", 599_000L)
        assertThat(pages).isEmpty()
    }

    @Test
    fun `a quiet symbol pages once when it reaches the threshold in session`() {
        val alerts = alerts()
        alerts.onUnhealthy("X", stale, FeedFault.STALE)
        alerts.onStillStale("X", 600_000L)
        alerts.onStillStale("X", 900_000L)
        assertThat(pages).containsExactly(
            "market data for X: no quote for 10m while its venue is in session; nothing open on X in " +
                "this session; new orders on X stay suppressed until quotes return",
        )
    }

    @Test
    fun `a stale quote pages at once when a position and orders are open on the symbol`() {
        exposure = SymbolExposure(positionOpen = true, workingOrders = 2)
        val alerts = alerts()
        alerts.onUnhealthy("X", stale, FeedFault.STALE)
        alerts.onStillStale("X", 900_000L)
        assertThat(pages).containsExactly(
            "market data unhealthy for X: $stale; position open and 2 working order(s) on X: " +
                "engine-held stops and exits cannot trigger without ticks",
        )
    }

    @Test
    fun `a held episode pages when exposure appears before the threshold`() {
        val alerts = alerts()
        alerts.onUnhealthy("X", stale, FeedFault.STALE)
        exposure = SymbolExposure(positionOpen = false, workingOrders = 1)
        alerts.onStillStale("X", 120_000L)
        assertThat(pages).containsExactly(
            "market data unhealthy for X: no quote for 2m; 1 working order(s) on X: " +
                "engine-held stops and exits cannot trigger without ticks",
        )
    }

    @Test
    fun `clock skew and outlier runs page at once with nothing open`() {
        val alerts = alerts()
        alerts.onUnhealthy("X", "broker clock 3h behind", FeedFault.CLOCK_SKEW)
        alerts.onUnhealthy("Y", "4 consecutive outlier tick(s) rejected", FeedFault.OUTLIER)
        assertThat(pages).containsExactly(
            "market data unhealthy for X: broker clock 3h behind",
            "market data unhealthy for Y: 4 consecutive outlier tick(s) rejected",
        )
    }

    @Test
    fun `a recovery re-arms the next episode`() {
        val alerts = alerts()
        alerts.onUnhealthy("X", stale, FeedFault.STALE)
        alerts.onStillStale("X", 600_000L)
        alerts.onRecovered("X", "fresh tick after stale", 600_500L)
        alerts.onStillStale("X", 700_000L)
        assertThat(pages).hasSize(1)

        alerts.onUnhealthy("X", stale, FeedFault.STALE)
        alerts.onStillStale("X", 660_000L)
        assertThat(pages)
            .hasSize(2)
            .last()
            .asString()
            .contains("no quote for 11m")
    }

    @Test
    fun `a zero quiet threshold pages every stale quote at once as before`() {
        val alerts = alerts(quietAlertAfterMs = 0L)
        alerts.onUnhealthy("X", stale, FeedFault.STALE)
        alerts.onStillStale("X", 900_000L)
        assertThat(pages).containsExactly("market data unhealthy for X: $stale; nothing open on X in this session")
    }
}
