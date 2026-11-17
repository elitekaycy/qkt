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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The end of a continuous chain stops the stream with a reason; a measured roll the chain ignores is a bug. */
class ContinuousLiveFeedEdgesTest {
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
    private val firstRoll = RollRecord(ms("2024-09-19T08:00:00Z"), "BTCUSDT_240927", "BTCUSDT_241227", "63000", "63800")
    private val history = RollHistory(root.root, "8d@08:00", listOf(firstRoll))

    private fun feed(
        ticks: List<Tick>,
        startMs: Long,
        measure: (com.qkt.derivatives.futures.RollTransition) -> LiveRoll,
    ): ContinuousLiveFeed {
        val queue = ArrayDeque(ticks)
        return ContinuousLiveFeed(
            chain = { ContinuousChain(root, catalog, history, ContinuousSelector.FRONT) },
            ticks = {
                object : TickFeed {
                    override fun next(): Tick? = queue.removeFirstOrNull()
                }
            },
            measure = measure,
            clock =
                object : Clock {
                    override fun now() = startMs
                },
            sleep = {},
        )
    }

    private fun tick(iso: String) =
        Tick(
            "BINANCE_UM:BTCUSDT_250328",
            BigDecimal("98000"),
            ms(iso),
            bid = BigDecimal("98000"),
            ask = BigDecimal("98000"),
        )

    @Test
    fun `a tick after the last contract ends the stream with its reason`() {
        val feed = feed(listOf(tick("2025-04-02T00:00:00Z")), ms("2024-12-19T07:00:00Z")) { error("no measure") }

        assertThat(feed.next()).isNull()

        assertThat(feed.terminalFailureReason()).contains("has no contract at").contains("BTCUSDT@front")
    }

    @Test
    fun `a roll reported measured that the chain still does not cover fails loudly`() {
        val feed =
            feed(listOf(tick("2024-12-19T08:00:01Z")), ms("2024-12-19T07:00:00Z")) {
                LiveRoll.Measured(RollRecord(it.atMs, "BTCUSDT_241227", "BTCUSDT_250328", "97000", "98500"))
            }

        assertThatThrownBy { feed.next() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("was measured but the chain does not cover it")
    }
}
