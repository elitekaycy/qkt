package com.qkt.instrument

import com.qkt.instrument.SlotFamilyFixture.ROOT
import com.qkt.instrument.SlotFamilyFixture.closeMs
import com.qkt.instrument.SlotFamilyFixture.symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FamilyDirectoryTest {
    private val directory = FamilyDirectory(SlotFamilyFixture.registry())

    @Test
    fun `a declared family lists its contracts with their terms`() {
        assertThat(directory.isFamily(ROOT)).isTrue()
        assertThat(directory.isFamily("KALSHI:OTHER")).isFalse()
        assertThat(directory.contracts(ROOT)).hasSize(12)
        assertThat(directory.contracts(ROOT).map { it.terms.root }).containsOnly(ROOT)
        assertThat(FamilyDirectory(NoopInstrumentRegistry).contracts(ROOT)).isEmpty()
        assertThat(FamilyDirectory(NoopInstrumentRegistry).isFamily(ROOT)).isFalse()
    }

    @Test
    fun `events open in close order, contracts in strike order, and leave when trading stops`() {
        val open = directory.openEvents(ROOT)

        assertThat(open.at(SlotFamilyFixture.OPEN_MS - 1)).isEmpty()
        val atTen = open.at(SlotFamilyFixture.ms("2024-03-04T10:00:00Z"))
        assertThat(atTen.map { it.event }).containsExactly("KXS-H1", "KXS-H2", "KXS-H3")
        assertThat(atTen.first().closeAtMs).isEqualTo(closeMs(1))
        assertThat(atTen.first().contracts.map { it.symbol })
            .containsExactly(symbol(1, 1), symbol(1, 2), symbol(1, 3), symbol(1, 4))
        assertThat(open.at(closeMs(1)).map { it.event }).containsExactly("KXS-H2", "KXS-H3")
        assertThat(open.at(closeMs(3))).isEmpty()
    }

    private fun contract(
        code: String,
        type: String,
        floor: String?,
        cap: String?,
        closeAtMs: Long = closeMs(1),
    ): FamilyContract {
        val row = SlotFamilyFixture.row(1, 1, predicate = CatalogStrike(type, floor, cap))
        val listed = row.copy(code = code, closeAtMs = closeAtMs, expiryMs = closeAtMs)
        return FamilyContract("KALSHI:$code", listed.terms(ROOT))
    }

    @Test
    fun `a contract listed after it closed never opens, and bounds order before codes`() {
        val open =
            OpenEvents(
                listOf(
                    contract("Z", "custom", null, null),
                    contract("A", "custom", null, null),
                    contract("B2", "between", "2", "3"),
                    contract("B1", "between", "2", "2.5"),
                    contract("L", "less", null, "1"),
                    contract("GONE", "greater", "9", null, closeAtMs = SlotFamilyFixture.OPEN_MS + 1),
                ),
            )

        val event = open.at(SlotFamilyFixture.OPEN_MS + 5).single()
        assertThat(event.contracts.map { it.symbol.substringAfter(':') }).containsExactly("L", "B1", "B2", "A", "Z")
    }
}
