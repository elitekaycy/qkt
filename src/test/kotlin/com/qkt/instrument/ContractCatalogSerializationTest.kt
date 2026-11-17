package com.qkt.instrument

import com.qkt.common.WireAsserts.decodes
import com.qkt.common.WireAsserts.refusesWithout
import com.qkt.common.WireAsserts.refusesWrongType
import com.qkt.common.WireAsserts.roundTrips
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ContractCatalogSerializationTest {
    private val bare = ListedContract("ESZ4", 5L)
    private val full = ListedContract("ESH5", 9L, "5000.5", "100", "7.5")

    @Test
    fun `a contract round trips with every optional set and none`() {
        roundTrips(bare)
        roundTrips(full)
        decodes("""{"symbol":"s","expiryMs":1}""", ListedContract("s", 1L))
        refusesWithout<ListedContract>("""{"symbol":"s","expiryMs":1}""", "symbol", "expiryMs")
        refusesWrongType<ListedContract>(
            """{"symbol":"s","expiryMs":1}""",
            "symbol",
            "expiryMs",
            "deliveryPrice",
            "lifetimeVolume",
            "peakOpenInterest",
        )
    }

    @Test
    fun `a contract reads its optional numbers exactly or as null`() {
        assertThat(bare.deliveryPriceOrNull()).isNull()
        assertThat(bare.lifetimeVolumeOrNull()).isNull()
        assertThat(bare.peakOpenInterestOrNull()).isNull()
        assertThat(full.deliveryPriceOrNull()).isEqualByComparingTo("5000.5")
        assertThat(full.lifetimeVolumeOrNull()).isEqualByComparingTo("100")
        assertThat(full.peakOpenInterestOrNull()).isEqualByComparingTo("7.5")
        assertThat(ListedContract("s", 1L, lifetimeVolume = "0", peakOpenInterest = "0").lifetimeVolumeOrNull())
            .isEqualByComparingTo("0")
    }

    @Test
    fun `a contract refuses values that cannot be right`() {
        assertThatThrownBy { ListedContract("s", 1L, lifetimeVolume = "-1") }
            .hasMessageContaining("lifetimeVolume must be a number >= 0: -1")
        assertThatThrownBy { ListedContract("s", 1L, lifetimeVolume = "x") }
            .hasMessageContaining("lifetimeVolume must be a number >= 0: x")
        assertThatThrownBy { ListedContract("s", 1L, peakOpenInterest = "-2") }
            .hasMessageContaining("peakOpenInterest must be a number >= 0")
        assertThatThrownBy { ListedContract(" ", 1L) }.hasMessageContaining("symbol must not be blank")
        assertThatThrownBy { ListedContract("s", 0L) }.hasMessageContaining("expiryMs must be > 0: 0")
        assertThatThrownBy { ListedContract("s", 1L, "0") }.hasMessageContaining("deliveryPrice must be > 0: 0")
        assertThatThrownBy { ListedContract("s", 1L, "abc") }.hasMessageContaining("deliveryPrice must be > 0: abc")
    }

    @Test
    fun `a catalog round trips, sorts by expiry and refuses duplicates`() {
        val catalog = ContractCatalog("CME:ES", listOf(full, bare))
        roundTrips(catalog)
        roundTrips(ContractCatalog("CME:ES", emptyList()))
        assertThat(catalog.sorted().contracts).containsExactly(bare, full)
        refusesWithout<ContractCatalog>("""{"root":"r","contracts":[]}""", "root", "contracts")
        refusesWrongType<ContractCatalog>("""{"root":"r","contracts":[]}""", "root", "contracts")
        assertThatThrownBy { ContractCatalog("CME:ES", listOf(bare, bare.copy(expiryMs = 6L))) }
            .hasMessageContaining("ContractCatalog CME:ES: duplicate contract 'ESZ4'")
    }
}
