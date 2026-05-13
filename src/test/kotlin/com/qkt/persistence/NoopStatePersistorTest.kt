package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NoopStatePersistorTest {
    @Test
    fun `legbook round-trip preserves primary and stack legs`() {
        val persistor = NoopStatePersistor()
        val book = LegBook("XAUUSDm")
        val primary =
            PositionLeg(
                legId = "leg-1",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.20"),
                entryPrice = BigDecimal("4700.0"),
                openedAt = 1000L,
                role = LegRole.PRIMARY,
            )
        val stack =
            PositionLeg(
                legId = "leg-2",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.06"),
                entryPrice = BigDecimal("4710.0"),
                openedAt = 2000L,
                role = LegRole.STACK,
                parentLegId = "leg-1",
            )
        book.add(primary)
        book.add(stack)
        persistor.saveLegBook("hedge", "XAUUSDm", book)
        val loaded = persistor.loadLegBook("hedge", "XAUUSDm")
        assertThat(loaded).isNotNull
        assertThat(loaded!!.legs).hasSize(2)
        assertThat(loaded.legs.first { it.role == LegRole.PRIMARY }.legId).isEqualTo("leg-1")
        assertThat(loaded.legs.first { it.role == LegRole.STACK }.parentLegId).isEqualTo("leg-1")
    }

    @Test
    fun `bracket pairs round-trip`() {
        val persistor = NoopStatePersistor()
        val pairs =
            listOf(
                BracketPair(
                    entryClientOrderId = "c-1",
                    stopLossClientOrderId = "c-1-sl",
                    takeProfitClientOrderId = "c-1-tp",
                    legId = "leg-1",
                ),
            )
        persistor.saveBracketPairs("hedge", pairs)
        assertThat(persistor.loadBracketPairs("hedge")).isEqualTo(pairs)
    }

    @Test
    fun `pending stacks round-trip preserves fired-at and firedLegId`() {
        val persistor = NoopStatePersistor()
        val tier =
            PersistedTier(
                index = 0,
                mfeThreshold = BigDecimal("10"),
                withinMs = 1_800_000L,
                stackQuantity = BigDecimal("0.06"),
                slDistance = BigDecimal("200"),
                tpDistance = BigDecimal("2000"),
                fired = true,
                firedAt = 1_500_000L,
                firedLegId = "leg-2",
            )
        persistor.savePendingStacks(
            "hedge",
            mapOf("leg-1" to PersistedTierState(primaryClientOrderId = "c-1", tiers = listOf(tier))),
        )
        val loaded = persistor.loadPendingStacks("hedge")
        assertThat(loaded).hasSize(1)
        assertThat(loaded["leg-1"]!!.tiers).hasSize(1)
        assertThat(loaded["leg-1"]!!.tiers[0].fired).isTrue
        assertThat(loaded["leg-1"]!!.tiers[0].firedLegId).isEqualTo("leg-2")
    }

    @Test
    fun `loadLegBook returns null when nothing persisted`() {
        val persistor = NoopStatePersistor()
        assertThat(persistor.loadLegBook("absent", "XAUUSDm")).isNull()
    }

    @Test
    fun `clearStrategy wipes all state for that strategy`() {
        val persistor = NoopStatePersistor()
        persistor.saveBracketPairs("hedge", listOf(BracketPair("e", "sl", "tp", "leg-1")))
        persistor.clearStrategy("hedge")
        assertThat(persistor.loadBracketPairs("hedge")).isEmpty()
    }
}
