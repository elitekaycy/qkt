package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.SignalEvent
import com.qkt.events.StructureClosed
import com.qkt.events.StructureEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import com.qkt.strategy.Signal
import com.qkt.strategy.StructureState
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A strategy's option structures across a restart: saved after each change, restored whole, closing on. */
class StructurePersistenceTest {
    private val shortPut = StructureFixtures.market("s", StructureFixtures.P81, Side.SELL)
    private val longPut = StructureFixtures.market("l", StructureFixtures.P78, Side.BUY)

    /** One session of strategy `st`: its bus, book and coordinator, saving through [persistor]. */
    private class Session(
        persistor: FileStatePersistor,
    ) {
        val bus = EventBus(FixedClock(5L), MonotonicSequenceGenerator())
        val published = mutableListOf<StructureEvent>()
        val book = StructureBook("st", StructureFixtures.registry, MarketPriceTracker()) { published += it }

        init {
            val kept = StructurePersistence("st", book, persistor).also { it.restore() }
            StructureCoordinator(
                bus,
                FixedClock(5L),
            ) {}.bind("st", book, kept::save) { bus.publish(SignalEvent(it, "st")) }
        }

        fun group(
            id: String,
            vararg legs: OrderRequest,
            closes: String? = null,
        ) = bus.publish(SignalEvent(Signal.SubmitGroup(id, "ps", legs.toList(), closes), strategyId = "st"))

        fun filled(
            leg: OrderRequest,
            price: String = "10",
        ) = bus.publish(
            BrokerEvent.OrderFilled(
                leg.id,
                leg.id,
                leg.symbol,
                leg.side,
                BigDecimal(price),
                leg.quantity,
                strategyId = "st",
            ),
        )
    }

    @Test
    fun `a structure closing when the session stops is restored whole and finishes closing after the restart`(
        @TempDir dir: Path,
    ) {
        val persistor = FileStatePersistor(dir)
        val before = Session(persistor)
        before.group("ps-1", longPut, shortPut)
        before.filled(longPut, "40")
        before.filled(shortPut, "90")
        val buyBack = StructureFixtures.market("c-s", StructureFixtures.P81, Side.BUY)
        val sellOut = StructureFixtures.market("c-l", StructureFixtures.P78, Side.SELL)
        before.group("close-1", buyBack, sellOut, closes = "ps-1")
        val held = before.book.live("ps")!!
        assertThat(held.state).isEqualTo(StructureState.CLOSING)

        val after = Session(persistor)

        assertThat(after.book.live("ps")).isEqualTo(held)
        after.filled(buyBack, "70")
        after.filled(sellOut, "30")
        val closed = after.published.filterIsInstance<StructureClosed>().single()
        assertThat(closed.realized).isEqualByComparingTo(BigDecimal("0.1").multiply(BigDecimal("10")))
        assertThat(after.book.live("ps")).isNull()
        assertThat(persistor.loadStructures("st")).isEmpty()
    }

    @Test
    fun `a strategy that never holds a structure never writes structure state`(
        @TempDir dir: Path,
    ) {
        val session = Session(FileStatePersistor(dir))

        session.filled(StructureFixtures.market("cfd-1", "EXNESS:XAUUSD", Side.BUY))

        assertThat(Files.exists(dir.resolve("st").resolve("structures.json"))).isFalse()
    }
}
