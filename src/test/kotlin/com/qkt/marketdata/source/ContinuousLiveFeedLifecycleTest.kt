package com.qkt.marketdata.source

import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.LiveRoll
import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The account feed's outages and failures stay visible through a continuous stream, across its rolls. */
class ContinuousLiveFeedLifecycleTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val catalog =
        ContractCatalog(
            root.root,
            listOf(
                ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                ListedContract("BTCUSDT_250328", ms("2025-03-28T08:00:00Z")),
            ),
        )
    private val first = RollRecord(ms("2024-09-19T08:00:00Z"), "BTCUSDT_240927", "BTCUSDT_241227", "63000", "63800")
    private val second = RollRecord(ms("2024-12-19T08:00:00Z"), "BTCUSDT_241227", "BTCUSDT_250328", "97000", "98500")
    private var history = RollHistory(root.root, "8d@08:00", listOf(first))
    private val now = ms("2024-12-19T07:00:00Z")

    /** An account feed with outages: it replays [ticks], and ends with [failure] when given. */
    private inner class AccountFeed(
        ticks: List<Tick>,
        private val failure: String? = null,
    ) : TickFeed,
        MarketDataLifecycleFeed {
        private val queue = ArrayDeque(ticks)
        val disconnect = mutableListOf<(MarketDataFeedScope) -> Unit>()
        val reconnect = mutableListOf<(MarketDataFeedScope) -> Unit>()

        override fun next(): Tick? = queue.removeFirstOrNull()

        override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
            disconnect += handler
        }

        override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
            reconnect += handler
        }

        override fun terminalFailureReason(): String? = failure.takeIf { queue.isEmpty() }
    }

    private fun tick(
        code: String,
        iso: String,
    ) = Tick("BINANCE_UM:$code", BigDecimal("97000"), ms(iso))

    private fun feed(
        accounts: ArrayDeque<AccountFeed>,
        answer: LiveRoll,
    ) = ContinuousLiveFeed(
        chain = { ContinuousChain(root, catalog, history, ContinuousSelector.FRONT) },
        ticks = { accounts.removeFirst() },
        measure = {
            answer.also {
                if (it is LiveRoll.Measured) {
                    history =
                        history.copy(
                            rolls = history.rolls + it.record,
                        )
                }
            }
        },
        clock =
            object : Clock {
                override fun now() = now
            },
        sleep = {},
    )

    @Test
    fun `outages of the account feed read now are passed on, and after a roll those of the new one`() {
        val before =
            AccountFeed(
                listOf(tick("BTCUSDT_241227", "2024-12-19T07:10:00Z"), tick("BTCUSDT_250328", "2024-12-19T08:00:05Z")),
            )
        val after = AccountFeed(listOf(tick("BTCUSDT_250328", "2024-12-19T08:02:00Z")))
        val feed = feed(ArrayDeque(listOf(before, after)), LiveRoll.Measured(second))
        val heard = mutableListOf<String>()
        feed.onDisconnect { heard += "down ${it.symbols}" }
        feed.onReconnect { heard += "up ${it.symbols}" }

        feed.next()
        before.disconnect.forEach { it(MarketDataFeedScope("gateway", listOf("BINANCE_UM:BTCUSDT_241227"))) }
        feed.next()
        before.reconnect.forEach { it(MarketDataFeedScope("gateway", listOf("old"))) }
        after.reconnect.forEach { it(MarketDataFeedScope("gateway", listOf("BINANCE_UM:BTCUSDT_250328"))) }

        assertThat(heard).containsExactly("down [BINANCE_UM:BTCUSDT_241227]", "up [BINANCE_UM:BTCUSDT_250328]")
        assertThat(feed.expectsContinuousDelivery).isTrue()
    }

    @Test
    fun `the account feed's failure, or a roll that cannot be measured, is the stream's terminal reason`() {
        val failing =
            feed(ArrayDeque(listOf(AccountFeed(emptyList(), failure = "gateway gone"))), LiveRoll.NotNext("unused"))
        val unmeasurable =
            feed(
                ArrayDeque(listOf(AccountFeed(listOf(tick("BTCUSDT_250328", "2024-12-19T08:00:05Z"))))),
                LiveRoll.NotNext("history gap"),
            )

        assertThat(failing.next()).isNull()
        assertThat(failing.terminalFailureReason()).isEqualTo("gateway gone")
        assertThat(unmeasurable.next()).isNull()
        assertThat(unmeasurable.terminalFailureReason()).contains("history gap")
    }
}
