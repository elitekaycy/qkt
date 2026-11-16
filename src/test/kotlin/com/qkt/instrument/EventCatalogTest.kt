package com.qkt.instrument

import com.qkt.instrument.EventFamilyFixture.ROOT
import com.qkt.instrument.EventFamilyFixture.row
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EventCatalogTest {
    @Test
    fun `a row becomes the contract's terms and symbol`() {
        val listed = row(1, 2, outcome = "1").copy(occursAtMs = 5L, occasion = "o", canCloseEarly = true)
        val terms = listed.terms(ROOT)

        assertThat(listed.qktSymbol(ROOT)).isEqualTo("KALSHI:KXT_E1_T2")
        assertThat(terms.event).isEqualTo("KXT-E1")
        assertThat(terms.predicate).isEqualTo(StrikePredicate(StrikeType.GREATER, BigDecimal("200"), null))
        assertThat(terms.expiryMs).isEqualTo(EventFamilyFixture.E1_EXPIRY_MS)
        assertThat(terms.occursAtMs).isEqualTo(5L)
        assertThat(terms.occasion).isEqualTo("o")
        assertThat(terms.canCloseEarly).isTrue()
        assertThat(terms.title).isEqualTo("above 200")
        assertThat(terms.fee).isEqualTo(ProbabilityFee(BigDecimal("0.07"), null, BigDecimal("0.01")))
        assertThat(terms.outcome).isEqualByComparingTo("1")
        assertThat(row(1, 1).terms(ROOT).outcome).isNull()
    }

    @Test
    fun `a fee row reads its maker rate`() {
        assertThat(CatalogFee("0.07", "0.0175", "0.01").fee().makerRate).isEqualByComparingTo("0.0175")
    }

    @Test
    fun `an invalid row fails naming its code`() {
        assertThatThrownBy { row(1, 1).copy(predicate = CatalogStrike("above")).terms(ROOT) }
            .hasMessageContaining("KXT-E1-T1")
            .hasMessageContaining("strike type 'above'")
        assertThatThrownBy { row(1, 1).copy(predicate = CatalogStrike("between", floor = "1")).terms(ROOT) }
            .hasMessageContaining("KXT-E1-T1")
        assertThatThrownBy { row(1, 1).copy(expiryMs = 1L).terms(ROOT) }.hasMessageContaining("expiryMs")
        assertThatThrownBy { row(1, 1).copy(outcome = "2").terms(ROOT) }.hasMessageContaining("outcome")
    }

    @Test
    fun `a catalog refuses a duplicate code and a code that is no symbol`() {
        assertThatThrownBy { EventCatalog(ROOT, listOf(row(1, 1), row(1, 1))) }.hasMessageContaining("duplicate")
        assertThatThrownBy { EventCatalog(ROOT, listOf(row(1, 1).copy(code = "a/b"))) }.hasMessageContaining("a/b")
    }

    @Test
    fun `sorted orders contracts by expiry then code`() {
        val catalog = EventCatalog(ROOT, listOf(row(2, 1), row(1, 3), row(1, 1))).sorted()
        assertThat(catalog.contracts.map { it.code }).containsExactly("KXT-E1-T1", "KXT-E1-T3", "KXT-E2-T1")
    }

    @Test
    fun `a later import fills an outcome and an empty one keeps it`() {
        val first = EventCatalog(ROOT, emptyList()).merge(listOf(row(1, 1), row(1, 2)))
        val settled = first.merge(listOf(row(1, 1, outcome = "1")))
        val again = settled.merge(listOf(row(1, 1).copy(title = "renamed")))

        assertThat(settled.contracts.first { it.code == "KXT-E1-T1" }.outcome).isEqualTo("1")
        assertThat(again.contracts.first { it.code == "KXT-E1-T1" }.outcome).isEqualTo("1")
        assertThat(again.contracts.first { it.code == "KXT-E1-T1" }.title).isEqualTo("renamed")
        assertThat(again.contracts).hasSize(2)
        assertThat(again.merge(listOf(row(1, 1, outcome = "1.0"))).contracts).hasSize(2)
    }

    @Test
    fun `an outcome contradicting a recorded one is refused`() {
        val settled = EventCatalog(ROOT, listOf(row(1, 1, outcome = "1")))
        assertThatThrownBy { settled.merge(listOf(row(1, 1, outcome = "0"))) }
            .hasMessageContaining("KXT-E1-T1")
            .hasMessageContaining("already resolved")
    }

    @Test
    fun `the store writes events json under the family's venue and reads it back`(
        @TempDir dir: Path,
    ) {
        val store = EventCatalogStore(dir)
        assertThat(store.read(ROOT)).isNull()
        store.write(EventFamilyFixture.catalog(mapOf("KXT-E1-T1" to "0")))

        assertThat(store.path(ROOT)).isEqualTo(dir.resolve("contracts/KALSHI/KXT.events.json"))
        val read = requireNotNull(store.read(ROOT))
        assertThat(read.contracts).hasSize(6)
        assertThat(read.contracts.first().outcome).isEqualTo("0")
        assertThat(Files.readString(store.path(ROOT))).contains("\"predicate\"").doesNotContain("\"occasion\"")
    }

    @Test
    fun `a malformed catalog file fails naming its path`(
        @TempDir dir: Path,
    ) {
        val store = EventCatalogStore(dir)
        Files.createDirectories(store.path(ROOT).parent)
        Files.writeString(store.path(ROOT), """{"root":"KALSHI:KXT","contracts":[{"code":"x"}]}""")
        assertThatThrownBy { store.read(ROOT) }.hasMessageContaining("KXT.events.json")
    }
}
