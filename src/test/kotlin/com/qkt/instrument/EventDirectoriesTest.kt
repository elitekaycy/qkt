package com.qkt.instrument

import com.qkt.instrument.SlotFamilyFixture.ROOT
import com.qkt.instrument.SlotFamilyFixture.symbol
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A session layering its catalogued families over a venue's live ones reads each family from the layer declaring it. */
class EventDirectoriesTest {
    private val other = EventFamily("KALSHI:KXO", "USD", BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE)

    private val live =
        object : InstrumentRegistry, EventDirectory {
            override fun lookup(qktSymbol: String): InstrumentMeta? = null

            override fun events(): EventDirectory = this

            override fun family(root: String): EventFamily? = other.takeIf { root == it.root }

            override fun contracts(root: String): List<String> = listOf("KALSHI:KXO_A")

            override fun aliveAt(
                root: String,
                atMs: Long,
            ): List<String> = listOf("KALSHI:KXO_A")
        }

    @Test
    fun `each family is answered by the first layer declaring it, an undeclared one by none`() {
        val events = requireNotNull(LayeredInstrumentRegistry(listOf(SlotFamilyFixture.registry(), live)).events())

        assertThat(events.family(ROOT)).isEqualTo(SlotFamilyFixture.FAMILY)
        assertThat(events.family("KALSHI:KXO")).isEqualTo(other)
        assertThat(events.contracts(ROOT)).contains(symbol(1, 1))
        assertThat(events.contracts("KALSHI:KXO")).containsExactly("KALSHI:KXO_A")
        assertThat(events.aliveAt("KALSHI:KXO", 0L)).containsExactly("KALSHI:KXO_A")
        assertThat(events.contracts("KALSHI:NONE")).isEmpty()
        assertThat(events.aliveAt("KALSHI:NONE", 0L)).isEmpty()
        assertThat(events.family("KALSHI:NONE")).isNull()
    }

    @Test
    fun `one layer with families is read directly, and none means no directory`() {
        val single = SlotFamilyFixture.registry()

        assertThat(LayeredInstrumentRegistry(listOf(single, StandardInstrumentRegistry)).events()).isSameAs(single)
        assertThat(LayeredInstrumentRegistry(listOf(StandardInstrumentRegistry)).events()).isNull()
    }
}
