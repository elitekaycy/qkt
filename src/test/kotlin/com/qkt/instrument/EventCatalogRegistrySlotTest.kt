package com.qkt.instrument

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A slot of a declared family resolves to the family's sizing, with no terms: it is a stream, never a contract. */
class EventCatalogRegistrySlotTest {
    private val registry = SlotFamilyFixture.registry()

    @Test
    fun `a declared family's slot sizes like its contracts and carries no terms`() {
        val meta = requireNotNull(registry.lookup("KALSHI:KXS@front.p70"))

        assertThat(meta.qktSymbol).isEqualTo("KALSHI:KXS@front.p70")
        assertThat(meta.contractSize).isEqualByComparingTo("1")
        assertThat(meta.pointSize).isEqualByComparingTo("0.01")
        assertThat(meta.currency).isEqualTo("USD")
        assertThat(meta.derivative).isNull()
    }

    @Test
    fun `a slot of another family or a malformed slot is not this registry's`() {
        assertThat(registry.lookup("KALSHI:OTHER@front.p70")).isNull()
        assertThat(registry.lookup("KALSHI:KXS@front.p99")).isNull()
        assertThat(registry.lookup(SlotFamilyFixture.symbol(1, 1))?.derivative).isInstanceOf(EventTerms::class.java)
    }
}
