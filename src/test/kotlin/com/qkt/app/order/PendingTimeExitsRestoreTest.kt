package com.qkt.app.order

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.persistence.PersistedTimeExit
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class PendingTimeExitsRestoreTest {
    private val book = OrderBook(isReferenced = { false }, reclaim = {})
    private val exits = TimedExitBook()
    private val pending =
        PendingTimeExits(
            book,
            FixedClock(1_000L),
            RecordingOrderOps(),
            exits,
            LoggerFactory.getLogger(PendingTimeExitsRestoreTest::class.java),
        )

    private fun exit(
        entryId: String?,
        protectiveIds: List<String> = emptyList(),
    ) = PersistedTimeExit(
        id = "exit-1",
        strategyId = "alpha",
        symbol = "XAUUSD",
        side = Side.BUY,
        quantity = BigDecimal.ONE,
        legId = null,
        ticket = null,
        deadlineMs = 0L,
        protectiveIds = protectiveIds,
        entryId = entryId,
        holdMs = 60_000L,
    )

    private fun track(id: String) {
        val request =
            OrderRequest.Limit(
                id,
                "XAUUSD",
                Side.BUY,
                BigDecimal.ONE,
                BigDecimal("2000"),
                TimeInForce.GTC,
                0L,
            )
        book.put(
            ManagedOrder(id = id, request = request, state = OrderState.WORKING, createdAt = 0L, lastUpdatedAt = 0L),
        )
    }

    @Test
    fun `an exit whose entry and protective orders were not restored is dropped`() {
        pending.restore(exit(entryId = "entry-1", protectiveIds = listOf("entry-1-sl")))

        assertThat(pending.isEmpty()).isTrue()
        assertThat(pending.targets("entry-1")).isFalse()
        assertThat(exits.byStrategy()).isEmpty()
    }

    @Test
    fun `an exit whose entry was restored waits for that entry again`() {
        track("entry-1")

        pending.restore(exit(entryId = "entry-1"))

        assertThat(pending.targets("entry-1")).isTrue()
        assertThat(exits.byStrategy().getValue("alpha").map { it.id }).containsExactly("exit-1")
    }

    @Test
    fun `an exit whose entry is gone but whose protective order was restored is kept`() {
        track("entry-1-sl")

        pending.restore(exit(entryId = "entry-1", protectiveIds = listOf("entry-1-sl")))

        assertThat(pending.targets("entry-1-sl")).isTrue()
        assertThat(exits.pending.keys).containsExactly("entry-1")
    }

    @Test
    fun `an exit that already armed is not a pending exit and is left alone`() {
        pending.restore(exit(entryId = null))

        assertThat(pending.isEmpty()).isTrue()
    }

    @Test
    fun `an armed exit is read back from the book by its id`() {
        val armed = exit(entryId = null)
        exits["exit-1"] = armed

        assertThat(exits["exit-1"]).isEqualTo(armed)
        assertThat(exits["other"]).isNull()
        assertThat(exits.remove("exit-1")).isEqualTo(armed)
        assertThat(exits.isEmpty()).isTrue()
    }
}
