package com.qkt.marketdata.source

import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.LiveRoll
import com.qkt.derivatives.futures.RollSchedule
import com.qkt.derivatives.futures.RollTransition
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
import org.junit.jupiter.api.Test

/** A continuous stream live serves what a backtest serves from the same ticks, and pauses only at a roll. */
class ContinuousLiveFeedTest {
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
    private val second: RollTransition = RollSchedule(catalog.contracts, root.roll!!).transitions[1]
    private val firstRoll = RollRecord(ms("2024-09-19T08:00:00Z"), "BTCUSDT_240927", "BTCUSDT_241227", "63000", "63800")
    private val secondRoll = RollRecord(second.atMs, "BTCUSDT_241227", "BTCUSDT_250328", "97000", "98500")
    private var history = RollHistory(root.root, "8d@08:00", listOf(firstRoll))
    private var now = ms("2024-12-19T07:00:00Z")
    private val opened = mutableListOf<List<String>>()
    private val measured = mutableListOf<RollTransition>()

    private fun chain() = ContinuousChain(root, catalog, history, ContinuousSelector.FRONT)

    private fun tick(
        code: String,
        iso: String,
        price: String,
    ) = Tick("BINANCE_UM:$code", BigDecimal(price), ms(iso), bid = BigDecimal(price), ask = BigDecimal(price))

    private fun feed(
        ticks: List<Tick>,
        answers: MutableList<LiveRoll>,
    ): ContinuousLiveFeed {
        val queue = ArrayDeque(ticks)
        return ContinuousLiveFeed(
            chain = ::chain,
            ticks = { symbols ->
                opened += symbols
                object : TickFeed {
                    override fun next(): Tick? = queue.removeFirstOrNull()?.also { now = maxOf(now, it.timestamp) }
                }
            },
            measure = { t ->
                measured += t
                answers.removeFirst().also {
                    if (it is LiveRoll.Measured) {
                        history =
                            history.copy(rolls = history.rolls + it.record)
                    }
                }
            },
            clock =
                object : Clock {
                    override fun now() = now
                },
            sleep = { now += it },
            retryMs = 15_000,
            measureForMs = 60_000,
        )
    }

    private fun drain(feed: TickFeed) = generateSequence { feed.next() }.toList()

    @Test
    fun `the front contract is served in the series, the next one never, and after a roll the new front is`() {
        val ticks =
            listOf(
                tick("BTCUSDT_241227", "2024-12-19T07:00:00Z", "97010"),
                tick("BTCUSDT_250328", "2024-12-19T07:30:00Z", "98510"),
                tick("BTCUSDT_250328", "2024-12-19T08:00:30Z", "98520"),
                tick("BTCUSDT_250328", "2024-12-19T08:02:00Z", "98530"),
                tick("BTCUSDT_241227", "2024-12-19T08:03:00Z", "97040"),
            )

        val served = drain(feed(ticks, mutableListOf(LiveRoll.Measured(secondRoll))))

        val beforeRoll =
            ContinuousChain(
                root,
                catalog,
                RollHistory(root.root, "8d@08:00", listOf(firstRoll)),
                ContinuousSelector.FRONT,
            )
        val before = ticks[0].inSpace(beforeRoll.spaceFor(1), beforeRoll.symbol)
        val after = chain().let { c -> ticks[3].inSpace(c.spaceFor(2), c.symbol) }
        assertThat(served).containsExactly(before, after)
        assertThat(served.map { it.symbol }.distinct()).containsExactly("BINANCE_UM:BTCUSDT@front")
        assertThat(measured).containsExactly(second)
        assertThat(opened).containsExactly(
            listOf("BINANCE_UM:BTCUSDT_241227", "BINANCE_UM:BTCUSDT_250328"),
            listOf("BINANCE_UM:BTCUSDT_250328"),
        )
    }

    @Test
    fun `a roll whose minute has not closed is measured again until it is`() {
        val ticks =
            listOf(
                tick("BTCUSDT_250328", "2024-12-19T08:00:01Z", "98520"),
                tick("BTCUSDT_250328", "2024-12-19T08:02:00Z", "98530"),
            )

        val served =
            drain(
                feed(
                    ticks,
                    mutableListOf(
                        LiveRoll.Unpriced("not yet"),
                        LiveRoll.Unpriced("not yet"),
                        LiveRoll.Measured(secondRoll),
                    ),
                ),
            )

        assertThat(measured).hasSize(3)
        assertThat(served.map { it.timestamp }).containsExactly(ms("2024-12-19T08:02:00Z"))
    }

    @Test
    fun `a roll the history cannot take, or one still unpriced when the wait runs out, ends the stream`() {
        val late =
            listOf(
                tick("BTCUSDT_250328", "2024-12-19T08:00:01Z", "98520"),
                tick("BTCUSDT_250328", "2024-12-19T08:02:00Z", "98530"),
            )

        assertThat(drain(feed(late, mutableListOf(LiveRoll.NotNext("gap"))))).isEmpty()
        assertThat(drain(feed(late, MutableList(10) { LiveRoll.Unpriced("not yet") }))).isEmpty()
        assertThat(measured.size).isLessThanOrEqualTo(1 + 60_000 / 15_000 + 1)
    }

    @Test
    fun `a roll the history already holds switches contracts without measuring, serving the new front at once`() {
        history = history.copy(rolls = listOf(firstRoll, secondRoll))
        val ticks =
            listOf(
                tick("BTCUSDT_241227", "2024-12-19T07:59:59Z", "97010"),
                tick("BTCUSDT_250328", "2024-12-19T08:00:01Z", "98520"),
                tick("BTCUSDT_241227", "2024-12-19T08:00:02Z", "97020"),
            )

        val served = drain(feed(ticks, mutableListOf()))

        assertThat(measured).isEmpty()
        assertThat(served.map { it.timestamp }).containsExactly(ticks[0].timestamp, ticks[1].timestamp)
        assertThat(served[1]).isEqualTo(ticks[1].inSpace(chain().spaceFor(2), chain().symbol))
        assertThat(opened.last()).containsExactly("BINANCE_UM:BTCUSDT_250328")
    }
}
