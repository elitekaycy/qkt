package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The slot selector table: event offsets, strike spellings and how each strike rule picks over quoted contracts. */
class SlotSelectorsTest {
    // A ladder lowest strike first: YES mids fall as the strike rises.
    private val ladder =
        listOf("0.90", "0.70", "0.50", "0.30", "0.10").mapIndexed { i, m -> SlotCandidate("T$i", BigDecimal(m)) }

    private fun pick(
        token: String,
        candidates: List<SlotCandidate> = ladder,
    ): String? = requireNotNull(SlotSelectors.strike(token)).pick(candidates)?.symbol

    @Test
    fun `front is the first open event and next the one after`() {
        assertThat(SlotSelectors.eventOffset("front")).isEqualTo(0)
        assertThat(SlotSelectors.eventOffset("next")).isEqualTo(1)
        assertThat(SlotSelectors.eventOffset("each")).isNull()
        assertThat(SlotSelectors.eventSpellings).isEqualTo("@front, @next")
    }

    @Test
    fun `strike spellings are p05 to p95, atm, aboveN, belowN and topN`() {
        for (token in listOf("p05", "p50", "p95", "atm", "above1", "below12", "top3")) {
            assertThat(SlotSelectors.strike(token)).describedAs(token).isNotNull()
        }
        for (token in listOf("p00", "p04", "p96", "p5", "p100", "above0", "below", "top0", "ATM", "x")) {
            assertThat(SlotSelectors.strike(token)).describedAs(token).isNull()
        }
        assertThat(SlotSelectors.strikeSpellings).isEqualTo("pNN (p05 ... p95), atm, aboveN, belowN, topN")
    }

    @Test
    fun `pNN picks the mid nearest NN percent and a tie goes to the lower strike`() {
        assertThat(pick("p70")).isEqualTo("T1")
        assertThat(pick("p75")).isEqualTo("T1")
        assertThat(pick("p80")).isEqualTo("T0")
        assertThat(pick("p60")).isEqualTo("T1")
        assertThat(pick("p05")).isEqualTo("T4")
        assertThat(pick("p70", emptyList())).isNull()
    }

    @Test
    fun `atm is p50 and aboveN or belowN step from it in strike order`() {
        assertThat(pick("atm")).isEqualTo("T2")
        assertThat(pick("above1")).isEqualTo("T3")
        assertThat(pick("above2")).isEqualTo("T4")
        assertThat(pick("above3")).isNull()
        assertThat(pick("below2")).isEqualTo("T0")
        assertThat(pick("below3")).isNull()
        assertThat(pick("above1", emptyList())).isNull()
    }

    @Test
    fun `topN is the Nth highest mid with ties to the lower strike`() {
        val field = listOf("0.20", "0.45", "0.45", "0.10").mapIndexed { i, m -> SlotCandidate("C$i", BigDecimal(m)) }

        assertThat(pick("top1", field)).isEqualTo("C1")
        assertThat(pick("top2", field)).isEqualTo("C2")
        assertThat(pick("top4", field)).isEqualTo("C3")
        assertThat(pick("top5", field)).isNull()
    }
}
