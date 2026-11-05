package com.qkt.connector.gateway

import com.qkt.broker.continuous.ContinuousContractBroker
import com.qkt.broker.continuous.ContractFillLog
import com.qkt.broker.continuous.ContractVenue
import com.qkt.broker.continuous.LaneStateStore
import com.qkt.broker.continuous.RollLedger
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.RollHistoryStore
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.ContinuousMarketSource
import com.qkt.marketdata.source.DailyExpiryChain
import com.qkt.marketdata.source.DailyExpiryChain.FRONT
import com.qkt.marketdata.source.DailyExpiryChain.ms
import com.qkt.marketdata.source.LiveContinuousStreams
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * qkt#1647: a daily-expiry `@front` lane holds ETH_USDC_5NOV26 when the daemon goes down on 11-03; the 11-04 and
 * 11-05 rolls pass, and 5NOV26 expires and settles at the venue (11-05 08:00, 2413.83) before the restart at 15:49.
 * Each [Process] is the qkt process: a gateway session, the stream's lane, its warmup and live feed, on the state on
 * disk. Its warmup reads bars across the missed rolls before the session is ready, as the strategy's deploy does.
 */
internal class ContinuousSettledInDowntimeTest {
    private val fake = FakeGateway(DailyExpiryChain.days.map { "ETH_USDC_${it}NOV26" })
    private val running = mutableListOf<GatewaySession>()

    @TempDir
    lateinit var dir: Path

    @AfterEach
    fun stop() {
        running.forEach { it.close() }
        fake.shutdown()
    }

    private val store by lazy { DailyExpiryChain.stored(RollHistoryStore(dir.resolve("rolls"))) }
    private val persistor by lazy { FileStatePersistor(dir.resolve("state")) }

    private inner class Process(
        startMs: Long,
        live: List<Tick> = emptyList(),
    ) {
        val clock = FixedClock(time = startMs)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val engine = java.util.concurrent.CopyOnWriteArrayList<BrokerEvent.OrderFilled>()
        val answered = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var venueBus: EventBus
        private val registry = DailyExpiryChain.registry(store)
        private val directory = requireNotNull(registry.futures())
        val chains = ContinuousChains(directory)
        private val account = DailyExpiryChain.account(live)
        private val streams = LiveContinuousStreams(chains, directory, store, account, clock)
        val broker =
            ContinuousContractBroker(
                bus,
                clock,
                chains,
                setOf(FRONT),
                RollLedger(),
                ContractFillLog(),
                LaneStateStore(persistor, "owner"),
            ) { b, _, positions ->
                venueBus = b
                val session =
                    GatewaySession(
                        GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 2),
                        GatewaySymbols("PAPER_DERIBIT:"),
                        clock,
                        GatewayIdentity("fake", "7", "demo"),
                        { setOf("s") },
                        streamFactory = { e, r, c -> GatewayStream(fake.url, "k", e, r, c, initialBackoffMs = 20) },
                        label = "PAPER_DERIBIT",
                    ).also { running += it }
                ContractVenue(GatewayBroker(session, b, clock, positions, "s"))
            }

        init {
            bus.subscribe<BrokerEvent.OrderFilled> { engine += it }
            bus.subscribe<BrokerEvent.OrderAccepted> { answered += it.clientOrderId }
            bus.subscribe<BrokerEvent.OrderRejected> { answered += it.clientOrderId }
        }

        /** The deploy warms up on the day before now, the lane meets its venue (the session is ready), ticks flow. */
        fun startsTrading() {
            val source = ContinuousMarketSource(account, chains, registry, streams)
            val day = TimeRange(Instant.ofEpochMilli(clock.time - 86_400_000L), Instant.ofEpochMilli(clock.time))
            source.bars(FRONT, TimeWindow.ONE_MINUTE, day).toList()
            broker.recoverPendingOrders(emptyList(), emptySet())
            broker.watchBookedLegs { emptyList() }
            val feed = source.liveTicks(listOf(FRONT))
            generateSequence { feed.next() }.forEach { bus.publish(TickEvent(it)) }
        }

        fun settles() = engine.filter { it.exitReason == ExitReason.EXPIRY }
    }

    private fun quote(
        contract: String,
        price: String,
        atMs: Long,
    ) = Tick("PAPER_DERIBIT:$contract", BigDecimal(price), atMs, bid = BigDecimal(price), ask = BigDecimal(price))

    /** Holds 0.03 long on 5NOV26, the front on 11-03 after the 08:00 roll, then goes down. */
    private fun holdsBeforeTheDowntime() {
        val at = ms("2026-11-03T09:00:00Z")
        val before = Process(at, listOf(quote("ETH_USDC_5NOV26", "2350", at)))
        before.startsTrading()
        val entry = "PAPER_DERIBIT:ETH_USDC_5NOV26"
        before.venueBus.publish(
            BrokerEvent.OrderFilled("entry", "v", entry, Side.BUY, BigDecimal("2350"), BigDecimal("0.03"), "s", at),
        )
        fake.venue.settlements += WireSettlement("ETH_USDC_5NOV26", "2413.83", ms("2026-11-05T08:00:00Z"))
    }

    private fun restart(): Process {
        val at = ms("2026-11-05T15:49:00Z")
        return Process(at, listOf(quote("ETH_USDC_7NOV26", "2440", at), quote("ETH_USDC_7NOV26", "2441", at + 5_000)))
            .also { it.startsTrading() }
    }

    @Test
    fun `the leg closes once at the venue's settlement price and the stream follows the current front`() {
        holdsBeforeTheDowntime()

        val after = restart()

        val settle = after.settles().single()
        val chain = requireNotNull(after.chains.chainFor(FRONT))
        val expired = requireNotNull(chain.indexOf("PAPER_DERIBIT:ETH_USDC_5NOV26"))
        assertThat(settle.symbol).isEqualTo(FRONT)
        assertThat(settle.side).isEqualTo(Side.SELL)
        assertThat(settle.quantity).isEqualByComparingTo("0.03")
        assertThat(settle.clientOrderId).isEqualTo("settle:PAPER_DERIBIT:ETH_USDC_5NOV26:s")
        assertThat(settle.price).isEqualByComparingTo(chain.spaceFor(expired).toContinuous(BigDecimal("2413.83")))
        assertThat(after.broker.streamStops("s")).isEmpty()
        assertThat(persistor.loadStreamLane("owner", FRONT)!!.contract).isEqualTo("PAPER_DERIBIT:ETH_USDC_7NOV26")
    }

    @Test
    fun `a later restart books nothing again, and the strategy trades the current front`() {
        holdsBeforeTheDowntime()
        restart()

        val again = restart()
        again.broker.submit(
            OrderRequest.Market("next", FRONT, Side.BUY, BigDecimal("0.03"), TimeInForce.GTC, again.clock.time, "s"),
        )

        // The gateway places orders, and hears their answers, on its own threads.
        val deadline = System.currentTimeMillis() + 5_000
        while ("next" !in again.answered && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(200)
        assertThat(again.settles()).isEmpty()
        assertThat(fake.submits.single().symbol).isEqualTo("ETH_USDC_7NOV26")
    }
}
