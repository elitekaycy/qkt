package com.qkt.instrument.universe

import com.qkt.dsl.sets.SymbolSetSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FilterHintsTest {
    private fun filter(where: String) =
        (SymbolSetSpec.parse("V:* WHERE $where TOP 1 BY symbol") as SymbolSetSpec.Filtered).filter

    @Test
    fun `a word every passing instrument must have is read from an equality, alone or under AND`() {
        assertThat(filter("kind = equity").wordRequiredOf(CatalogField.KIND)).isEqualTo("equity")
        assertThat(filter("kind = \"Spot\"").wordRequiredOf(CatalogField.KIND)).isEqualTo("spot")
        assertThat(filter("category = Stocks AND kind = event").wordRequiredOf(CatalogField.KIND)).isEqualTo("event")
        assertThat(filter("kind = spot AND category = Metals").wordRequiredOf(CatalogField.KIND)).isEqualTo("spot")
    }

    @Test
    fun `no word is required under OR, NOT, other operators, other fields or a number`() {
        assertThat(filter("kind = spot OR kind = equity").wordRequiredOf(CatalogField.KIND)).isNull()
        assertThat(filter("NOT kind = spot").wordRequiredOf(CatalogField.KIND)).isNull()
        assertThat(filter("kind != spot").wordRequiredOf(CatalogField.KIND)).isNull()
        assertThat(filter("category = Metals").wordRequiredOf(CatalogField.KIND)).isNull()
        assertThat(filter("kind IN (spot, equity)").wordRequiredOf(CatalogField.KIND)).isNull()
        assertThat(filter("contract_size = 100").wordRequiredOf(CatalogField.CONTRACT_SIZE)).isNull()
    }
}
