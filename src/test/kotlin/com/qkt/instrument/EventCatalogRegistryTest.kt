package com.qkt.instrument

import com.qkt.instrument.EventFamilyFixture.FAMILY
import com.qkt.instrument.EventFamilyFixture.ROOT
import com.qkt.instrument.EventFamilyFixture.ms
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EventCatalogRegistryTest {
    private val registry = EventCatalogRegistry(listOf(FAMILY), mapOf(ROOT to EventFamilyFixture.catalog()))

    @Test
    fun `every catalogued contract is a one-dollar instrument with its event terms`() {
        val meta = requireNotNull(registry.lookup("KALSHI:KXT_E2_T3"))

        assertThat(meta.contractSize).isEqualByComparingTo("1")
        assertThat(meta.pointSize).isEqualByComparingTo("0.01")
        assertThat(meta.digits).isEqualTo(2)
        assertThat(meta.volumeStep).isEqualByComparingTo("1")
        assertThat(meta.currency).isEqualTo("USD")
        assertThat((meta.derivative as EventTerms).predicate.floor).isEqualByComparingTo("300")
        assertThat(registry.lookup("KALSHI:OTHER")).isNull()
    }

    @Test
    fun `the directory lists a family's contracts in expiry order`() {
        val events = requireNotNull(registry.events())
        assertThat(events.family(ROOT)).isEqualTo(FAMILY)
        assertThat(events.family("KALSHI:NONE")).isNull()
        assertThat(events.contracts(ROOT)).containsExactly(
            "KALSHI:KXT_E1_T1",
            "KALSHI:KXT_E1_T2",
            "KALSHI:KXT_E1_T3",
            "KALSHI:KXT_E2_T1",
            "KALSHI:KXT_E2_T2",
            "KALSHI:KXT_E2_T3",
        )
        assertThat(events.contracts("KALSHI:NONE")).isEmpty()
    }

    @Test
    fun `alive at t means opened at or before t and not yet expired`() {
        assertThat(registry.aliveAt(ROOT, ms("2024-03-04T07:59:59Z"))).isEmpty()
        assertThat(registry.aliveAt(ROOT, EventFamilyFixture.OPEN_MS)).hasSize(6)
        assertThat(registry.aliveAt(ROOT, EventFamilyFixture.E1_EXPIRY_MS))
            .containsExactly("KALSHI:KXT_E2_T1", "KALSHI:KXT_E2_T2", "KALSHI:KXT_E2_T3")
        assertThat(registry.aliveAt(ROOT, EventFamilyFixture.E2_EXPIRY_MS)).isEmpty()
    }

    @Test
    fun `a contract of a declared family missing from its catalog names the import`() {
        val reason = registry.missingReason("KALSHI:KXT_E9_T1")
        assertThat(reason).contains("contracts/KALSHI/KXT.events.json").contains("--kind events")
        assertThat(registry.missingReason("KALSHI:KXT_E1_T1")).isNull()
        assertThat(registry.missingReason("BINANCE:BTCUSDT")).isNull()
    }

    @Test
    fun `two families claiming one symbol are refused`() {
        val twin = FAMILY.copy(root = "KALSHI:KXT2")
        val clash = EventCatalog("KALSHI:KXT2", listOf(EventFamilyFixture.row(1, 1)))
        assertThatThrownBy {
            EventCatalogRegistry(listOf(FAMILY, twin), mapOf(ROOT to EventFamilyFixture.catalog(), twin.root to clash))
        }.hasMessageContaining("more than one family")
    }

    @Test
    fun `load reads each family's catalog from the data root`(
        @TempDir dir: Path,
    ) {
        EventCatalogStore(dir).write(EventFamilyFixture.catalog())
        val loaded = EventCatalogRegistry.load(listOf(FAMILY, FAMILY.copy(root = "KALSHI:EMPTY")), dir)
        assertThat(loaded.contracts(ROOT)).hasSize(6)
        assertThat(loaded.contracts("KALSHI:EMPTY")).isEmpty()
    }

    @Test
    fun `a layered registry answers events from the layer that has them`() {
        val layered = LayeredInstrumentRegistry(listOf(NoopInstrumentRegistry, registry))
        assertThat(layered.events()).isSameAs(registry)
        assertThat(LayeredInstrumentRegistry(listOf(NoopInstrumentRegistry)).events()).isNull()
        assertThat(NoopInstrumentRegistry.events()).isNull()
    }

    @Test
    fun `a family's spec is validated`() {
        assertThatThrownBy { FAMILY.copy(root = "KXT") }.hasMessageContaining("VENUE:FAMILY")
        assertThatThrownBy { FAMILY.copy(currency = "usd") }.hasMessageContaining("currency")
        assertThatThrownBy { FAMILY.copy(tickSize = BigDecimal.ONE) }.hasMessageContaining("tickSize")
        assertThatThrownBy { FAMILY.copy(volumeStep = BigDecimal.ZERO) }.hasMessageContaining("volumeStep")
        assertThatThrownBy { FAMILY.copy(volumeMin = BigDecimal("0.5")) }.hasMessageContaining("volumeMin")
        assertThat(FAMILY.venue).isEqualTo("KALSHI")
    }
}
