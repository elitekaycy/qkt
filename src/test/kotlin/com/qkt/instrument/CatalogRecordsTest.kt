package com.qkt.instrument

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CatalogRecordsTest {
    private val call = OptionListing("BTC_USDC-2OCT26-92000-C", "92000", "call", 1L)
    private val put = OptionListing("BTC_USDC-2OCT26-92000-P", "92000", "put", 1L)

    @Test
    fun `an option listing needs a known right and a positive numeric strike`() {
        assertThatThrownBy { OptionListing("X", "1", "straddle", 1L) }.hasMessageContaining("right must be call or put")
        assertThatThrownBy { OptionListing("X", "lots", "call", 1L) }.hasMessageContaining("strike must be > 0: lots")
        assertThatThrownBy { OptionListing("X", "-5", "put", 1L) }.hasMessageContaining("strike must be > 0: -5")
        assertThat(put.toContract().right).isEqualTo(OptionRight.PUT)
        assertThat(call.toContract().right).isEqualTo(OptionRight.CALL)
    }

    @Test
    fun `an option catalog refuses duplicates and non-positive delivery prices`() {
        assertThatThrownBy { OptionCatalog("DERIBIT:BTC_USDC", listOf(call, call)) }.hasMessageContaining("duplicate")
        assertThatThrownBy { OptionCatalog("DERIBIT:BTC_USDC", listOf(call), mapOf("2026-10-02" to "n/a")) }
            .hasMessageContaining("delivery price on 2026-10-02 must be > 0: n/a")
        assertThatThrownBy { OptionCatalog("DERIBIT:BTC_USDC", listOf(call), mapOf("2026-10-02" to "0")) }
            .hasMessageContaining("must be > 0: 0")
    }

    @Test
    fun `an option catalog round-trips through json with and without delivery prices`() {
        val bare = OptionCatalog("DERIBIT:BTC_USDC", listOf(call, put))
        val settled = bare.copy(deliveryPrices = mapOf("2026-10-02" to "95000"))

        val bareJson = Json.encodeToString(OptionCatalog.serializer(), bare)

        assertThat(bareJson).doesNotContain("deliveryPrices")
        assertThat(Json.decodeFromString(OptionCatalog.serializer(), bareJson)).isEqualTo(bare)
        val settledJson = Json.encodeToString(OptionCatalog.serializer(), settled)
        assertThat(Json.decodeFromString(OptionCatalog.serializer(), settledJson)).isEqualTo(settled)
    }

    @Test
    fun `a listed contract round-trips its optional figures through json`() {
        val bare = ListedContract("ESZ6", 5L)
        val full = ListedContract("ESZ6", 5L, deliveryPrice = "5000.25", lifetimeVolume = "10", peakOpenInterest = "3")
        val catalog = ContractCatalog("CME:ES", listOf(full, bare.copy(symbol = "ESH7")))

        val json = Json.encodeToString(ContractCatalog.serializer(), catalog)

        assertThat(Json.decodeFromString(ContractCatalog.serializer(), json)).isEqualTo(catalog)
        assertThat(
            Json.encodeToString(ListedContract.serializer(), bare),
        ).isEqualTo("""{"symbol":"ESZ6","expiryMs":5}""")
        assertThat(full.peakOpenInterestOrNull()).isEqualByComparingTo("3")
        assertThat(bare.peakOpenInterestOrNull()).isNull()
        assertThat(bare.lifetimeVolumeOrNull()).isNull()
    }

    @Test
    fun `a listed contract refuses a delivery price that is not a positive number`() {
        assertThatThrownBy { ListedContract("ESZ6", 5L, deliveryPrice = "soon") }
            .hasMessageContaining("deliveryPrice must be > 0: soon")
        assertThatThrownBy { ListedContract("ESZ6", 5L, deliveryPrice = "-1") }
            .hasMessageContaining("deliveryPrice must be > 0: -1")
        assertThatThrownBy { ContractCatalog("CME:ES", listOf(ListedContract("ESZ6", 5L), ListedContract("ESZ6", 6L))) }
            .hasMessageContaining("duplicate contract 'ESZ6'")
    }

    @Test
    fun `a roll record needs positive numeric prices on both sides`() {
        assertThatThrownBy { RollRecord(1L, "A", "B", "x", "1") }.hasMessageContaining("fromPrice must be > 0: x")
        assertThatThrownBy { RollRecord(1L, "A", "B", "0", "1") }.hasMessageContaining("fromPrice must be > 0: 0")
        assertThatThrownBy { RollRecord(1L, "A", "B", "1", "y") }.hasMessageContaining("toPrice must be > 0: y")
        assertThatThrownBy { RollRecord(1L, "A", "B", "1", "-2") }.hasMessageContaining("toPrice must be > 0: -2")
        val history = RollHistory("CME:ES", "8d@08:00", listOf(RollRecord(1L, "A", "B", "10", "11")))
        assertThat(history.find(1L, "A", "B")?.toPriceValue()).isEqualByComparingTo("11")
        assertThat(history.find(1L, "A", "C")).isNull()
        assertThat(history.find(2L, "A", "B")).isNull()
        assertThat(history.find(1L, "Z", "B")).isNull()
    }
}
