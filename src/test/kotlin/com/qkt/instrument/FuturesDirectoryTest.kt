package com.qkt.instrument

import java.math.BigDecimal
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FuturesDirectoryTest {
    private val root =
        FuturesRoot(
            "CME:ES",
            "USD",
            BigDecimal("50"),
            BigDecimal("0.25"),
            BigDecimal.ONE,
            BigDecimal.ONE,
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(14, 0), PriceAdjustment.PANAMA),
        )
    private val history = RollHistory("CME:ES", "8d@14:00", emptyList())
    private val catalogs = ContractCatalogRegistry(listOf(root), emptyMap(), mapOf("CME:ES" to history))

    @Test
    fun `a layered registry exposes the futures directory of its futures layer`() {
        val layered = LayeredInstrumentRegistry(listOf(StandardInstrumentRegistry, catalogs))
        val futures = layered.futures()
        assertThat(futures?.root("CME:ES")).isEqualTo(root)
        assertThat(futures?.history("CME:ES")).isEqualTo(history)
        assertThat(futures?.rootOfContinuous("CME:ES@front")).isEqualTo("CME:ES")
        assertThat(futures?.rootOfContinuous("CME:ESZ6")).isNull()
    }

    @Test
    fun `registries without futures expose none`() {
        assertThat(StandardInstrumentRegistry.futures()).isNull()
        assertThat(LayeredInstrumentRegistry(listOf(StandardInstrumentRegistry)).futures()).isNull()
    }
}
