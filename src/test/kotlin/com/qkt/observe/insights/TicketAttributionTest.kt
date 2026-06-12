package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TicketAttributionTest {
    @Test
    fun `record then ownerOf round-trips`() {
        val map = TicketAttribution()
        map.record("2832831596", "hedge_straddle")
        assertThat(map.ownerOf("2832831596")).isEqualTo("hedge_straddle")
        assertThat(map.ownerOf("999")).isNull()
    }

    @Test
    fun `record ignores blank tickets and strategy ids`() {
        val map = TicketAttribution()
        map.record(null, "hedge_straddle")
        map.record("", "hedge_straddle")
        map.record("123", null)
        map.record("123", "")
        assertThat(map.ownerOf("123")).isNull()
    }

    @Test
    fun `retainAll drops tickets the broker no longer reports`() {
        val map = TicketAttribution()
        map.record("1", "a")
        map.record("2", "b")
        map.retainAll(setOf("2"))
        assertThat(map.ownerOf("1")).isNull()
        assertThat(map.ownerOf("2")).isEqualTo("b")
    }

    @Test
    fun `fromComment matches a venue-truncated prefix`() {
        val map = TicketAttribution()
        val deployed = listOf("hedge_straddle", "latch_stack")
        assertThat(map.fromComment("dsl-hedge_stradd", deployed)).isEqualTo("hedge_straddle")
    }

    @Test
    fun `fromComment matches when the deployed id is a prefix of the comment`() {
        val map = TicketAttribution()
        // Comment carries the full dsl-<id>-<n> shape; the deployed id is its prefix.
        assertThat(map.fromComment("dsl-hedge_straddle-12", listOf("hedge_straddle"))).isEqualTo("hedge_straddle")
    }

    @Test
    fun `fromComment returns null on ambiguity`() {
        val map = TicketAttribution()
        val deployed = listOf("hedge_straddle", "hedge_straddle_v2")
        assertThat(map.fromComment("dsl-hedge_str", deployed)).isNull()
    }

    @Test
    fun `fromComment returns null for null, blank, or unmatched comments`() {
        val map = TicketAttribution()
        assertThat(map.fromComment(null, listOf("a"))).isNull()
        assertThat(map.fromComment("dsl-", listOf("a"))).isNull()
        assertThat(map.fromComment("dsl-zzz", listOf("hedge_straddle"))).isNull()
    }
}
