package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TicketAttributionAliasTest {
    @Test
    fun `blank, missing or self aliases are ignored`() {
        val attribution = TicketAttribution()
        attribution.alias(null, "book:s0")
        attribution.alias(" ", "book:s0")
        attribution.alias("gold", null)
        attribution.alias("gold", "")
        attribution.alias("book:s0", "book:s0")

        assertThat(attribution.fromComment("dsl-gold", listOf("book:s0"))).isNull()
        assertThat(attribution.fromComment("dsl-book:s0", listOf("book:s0"))).isEqualTo("book:s0")
    }

    @Test
    fun `an alias to a strategy no longer deployed does not resolve`() {
        val attribution = TicketAttribution()
        attribution.alias("gold", "book:s0")

        assertThat(attribution.fromComment("dsl-gold", listOf("book:s1"))).isNull()
        assertThat(attribution.fromComment("dsl-gold", listOf("book:s0"))).isEqualTo("book:s0")
    }

    @Test
    fun `a ticket seen again resets its missing count`() {
        val attribution = TicketAttribution()
        attribution.record("t1", "alpha")
        attribution.retainAll(emptySet())
        attribution.retainAll(setOf("t1"))
        attribution.record(null, "alpha")

        assertThat(attribution.ownerOf("t1")).isEqualTo("alpha")
    }
}
