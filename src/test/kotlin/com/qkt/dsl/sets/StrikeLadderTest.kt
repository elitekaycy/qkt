package com.qkt.dsl.sets

import com.qkt.instrument.EventContracts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.StrikePredicate
import com.qkt.instrument.StrikeType
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrikeLadderTest {
    private val t0 = 1_794_002_400_000L

    private fun contract(
        code: String,
        type: StrikeType,
        floor: String? = null,
        cap: String? = null,
        event: String = "E1",
        openAtMs: Long = t0,
        expiryMs: Long = t0 + 3_600_000L,
    ): InstrumentMeta =
        EventContracts.meta(
            "KALSHI:$code",
            EventContracts.terms().copy(
                event = event,
                predicate = StrikePredicate(type, floor?.let(::BigDecimal), cap?.let(::BigDecimal)),
                openAtMs = openAtMs,
                closeAtMs = expiryMs,
                expiryMs = expiryMs,
            ),
        )

    private val below = contract("BELOW73", StrikeType.LESS, cap = "73")
    private val b73 = contract("B73", StrikeType.BETWEEN, "73", "74")
    private val b74 = contract("B74", StrikeType.BETWEEN, "74", "75")
    private val above = contract("ABOVE75", StrikeType.GREATER_OR_EQUAL, floor = "75")

    @Test
    fun `an event's ladder runs from the uncapped-below bucket up through the ranges to the open top`() {
        val ladder = StrikeLadder(listOf(above, b74, below, b73))
        assertThat(ladder.neighbour("BELOW73", 1, t0)).isEqualTo("B73")
        assertThat(ladder.neighbour("B73", 1, t0)).isEqualTo("B74")
        assertThat(ladder.neighbour("B74", 1, t0)).isEqualTo("ABOVE75")
        assertThat(ladder.neighbour("ABOVE75", 1, t0)).isNull()
        assertThat(ladder.neighbour("ABOVE75", -1, t0)).isEqualTo("B74")
        assertThat(ladder.neighbour("B73", -1, t0)).isEqualTo("BELOW73")
        assertThat(ladder.neighbour("BELOW73", -1, t0)).isNull()
    }

    @Test
    fun `only contracts of the same event listed at the read are rungs`() {
        val later = contract("B74", StrikeType.BETWEEN, "74", "75", openAtMs = t0 + 60_000L)
        val expired = contract("B75", StrikeType.BETWEEN, "75", "76", expiryMs = t0 + 60_000L)
        val other = contract("E2B74", StrikeType.BETWEEN, "73.5", "74", event = "E2")
        val ladder = StrikeLadder(listOf(b73, later, expired, other, above))
        // At t0 B74 is not listed yet, so the next listed strike up is B75; a minute later B75 has expired.
        assertThat(ladder.neighbour("B73", 1, t0)).isEqualTo("B75")
        assertThat(ladder.neighbour("B73", 1, t0 + 60_000L)).isEqualTo("B74")
        assertThat(ladder.neighbour("B74", 1, t0 + 60_000L)).isEqualTo("ABOVE75")
        assertThat(ladder.neighbour("B73", -1, t0)).isNull()
        assertThat(StrikeLadder(listOf(b73, expired)).neighbour("B73", 1, t0 + 60_000L)).isNull()
    }

    @Test
    fun `equal strikes are skipped, ties at the neighbour go to the first symbol, and a custom question has no rung`() {
        val twin = contract("A73", StrikeType.BETWEEN, "73", "74")
        val upper = contract("Z74", StrikeType.BETWEEN, "74", "75")
        val custom = contract("CUSTOM", StrikeType.CUSTOM)
        val ladder = StrikeLadder(listOf(b73, twin, b74, upper, custom, below))
        assertThat(ladder.neighbour("B73", 1, t0)).isEqualTo("B74")
        assertThat(ladder.neighbour("Z74", -1, t0)).isEqualTo("A73")
        assertThat(ladder.neighbour("B73", -1, t0)).isEqualTo("BELOW73")
        assertThat(ladder.neighbour("CUSTOM", 1, t0)).isNull()
        assertThat(ladder.neighbour("XAUUSD", 1, t0)).isNull()
        val capped = contract("UNDER74", StrikeType.LESS, cap = "74")
        assertThat(StrikeLadder(listOf(below, capped)).neighbour("BELOW73", 1, t0)).isEqualTo("UNDER74")
        assertThat(StrikeLadder(listOf(above, contract("UP", StrikeType.GREATER, "75"))).neighbour("UP", 1, t0))
            .isNull()
    }
}
