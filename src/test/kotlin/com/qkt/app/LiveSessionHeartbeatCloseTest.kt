package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.events.CandleEvent
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The 1Hz heartbeat closes a quiet bar on the wall clock. It must never overtake a tick
 * that precedes it in event time (#1058): ticks already queued are drained first, and the
 * close lags the clock by [LiveSession.DEFAULT_CANDLE_CLOSE_GRACE_MS] so a tick still in
 * flight from the poller lands in its own bar instead of being rejected as late.
 */
class LiveSessionHeartbeatCloseTest {
    private class ControllableSource : MarketSource {
        private val queue = LinkedBlockingQueue<Any>()
        private val end = Any()

        override val name = "controllable"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        fun emit(tick: Tick) {
            queue.put(tick)
        }

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                override fun next(): Tick? {
                    val value = queue.take()
                    return if (value === end) null else value as Tick
                }

                override fun close() {
                    queue.offer(end)
                }
            }
    }

    private class Harness {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val source = ControllableSource()
        val closed = CopyOnWriteArrayList<CandleEvent>()
        val handle: LiveSessionHandle

        init {
            bus.subscribe<CandleEvent> { closed += it }
            val strategy =
                object : Strategy {
                    override fun onTick(
                        tick: Tick,
                        ctx: StrategyContext,
                        emit: (Signal) -> Unit,
                    ) {}
                }
            handle =
                LiveSession(
                    strategies = listOf("s" to strategy),
                    source = source,
                    symbols = listOf("X"),
                    clock = clock,
                    calendar = TradingCalendar.crypto(),
                    candleWindow = TimeWindow.ONE_MINUTE,
                    busOverride = bus,
                    scheduleHeartbeatIntervalMs = 5L,
                ).start()
        }

        fun tick(
            ts: Long,
            price: String,
        ) = source.emit(Tick("X", Money.of(price), ts))

        fun awaitClosed(count: Int): Boolean {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < deadline) {
                if (closed.size >= count) return true
                Thread.sleep(5L)
            }
            return closed.size >= count
        }

        fun settle() = Thread.sleep(60L)

        fun stop() = handle.stop()
    }

    @Test
    fun `a tick in flight at the boundary lands in its bar and is not counted as dropped`() {
        val h = Harness()
        try {
            h.tick(1_000L, "100")
            h.tick(59_950L, "101")
            h.settle()
            // Wall clock passes the boundary: the heartbeat runs but must not close yet (grace).
            h.clock.advanceTo(60_010L)
            h.settle()
            assertThat(h.closed).isEmpty()
            // The last pre-boundary tick arrives one poll round late.
            h.tick(59_990L, "102")
            h.settle()
            // Grace elapsed: the bar closes with every pre-boundary tick inside it.
            h.clock.advanceTo(60_600L)

            assertThat(h.awaitClosed(1)).isTrue()
            val bar = h.closed.single().candle
            assertThat(bar.startTime).isEqualTo(0L)
            assertThat(bar.close).isEqualByComparingTo(Money.of("102"))
            assertThat(h.handle.droppedTicks).isZero()
        } finally {
            h.stop()
        }
    }

    @Test
    fun `a tick older than the grace after a closed bar is still a late drop`() {
        val h = Harness()
        try {
            h.tick(1_000L, "100")
            h.settle()
            h.clock.advanceTo(61_000L)
            assertThat(h.awaitClosed(1)).isTrue()

            h.tick(59_000L, "99")
            h.settle()

            assertThat(h.closed).hasSize(1)
            assertThat(h.handle.droppedTicks).isEqualTo(1L)
        } finally {
            h.stop()
        }
    }

    @Test
    fun `a tick-driven close is exact and does not wait for the grace`() {
        val h = Harness()
        try {
            h.tick(1_000L, "100")
            h.tick(60_100L, "103")

            assertThat(h.awaitClosed(1)).isTrue()
            val bar = h.closed.single().candle
            assertThat(bar.startTime).isEqualTo(0L)
            assertThat(bar.close).isEqualByComparingTo(Money.of("100"))
            assertThat(h.handle.droppedTicks).isZero()
        } finally {
            h.stop()
        }
    }
}
