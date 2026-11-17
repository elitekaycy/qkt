package com.qkt.instrument

import com.qkt.common.WireAsserts.decodes
import com.qkt.common.WireAsserts.refusesWithout
import com.qkt.common.WireAsserts.refusesWrongType
import com.qkt.common.WireAsserts.roundTrips
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EventCatalogSerializationTest {
    private val root = "KALSHI:KXINXU"
    private val strike = CatalogStrike("greater_or_equal", "7425")
    private val bare = ListedEvent("KX-A", "EV", strike, 1L, 2L, 3L)
    private val full =
        ListedEvent(
            "KX-B",
            "EV",
            CatalogStrike("between", "1", "2"),
            1L,
            2L,
            3L,
            occursAtMs = 4L,
            occasion = "o",
            canCloseEarly = true,
            exclusive = true,
            frequency = "daily",
            title = "t",
            fee = CatalogFee("0.07", "0.01", "0.01"),
            outcome = "1",
        )

    @Test
    fun `strike and fee round trip with and without optional bounds`() {
        roundTrips(strike)
        roundTrips(CatalogStrike("none"))
        roundTrips(CatalogFee("0.07", "0.01", "0.01"))
        roundTrips(CatalogFee("0.07", rounding = "0.01"))
        decodes("""{"type":"less","cap":"2"}""", CatalogStrike("less", null, "2"))
        decodes("""{"taker":"0.1","rounding":"0.01"}""", CatalogFee("0.1", null, "0.01"))
        refusesWithout<CatalogStrike>("""{"type":"none"}""", "type")
        refusesWithout<CatalogFee>("""{"taker":"0.1","rounding":"0.01"}""", "taker", "rounding")
        refusesWrongType<CatalogStrike>("""{"type":"none"}""", "floor", "cap")
        refusesWrongType<CatalogFee>("""{"taker":"0.1","rounding":"0.01"}""", "maker")
    }

    @Test
    fun `a strike and a fee read as the engine's values`() {
        assertThat(strike.predicate()).isEqualTo(StrikePredicate(StrikeType.GREATER_OR_EQUAL, BigDecimal("7425"), null))
        assertThat(CatalogStrike("less", cap = "2").predicate().cap).isEqualByComparingTo("2")
        assertThat(CatalogStrike("custom").predicate().type).isEqualTo(StrikeType.CUSTOM)
        assertThatThrownBy { CatalogStrike("sideways").predicate() }
            .hasMessageContaining("strike type 'sideways' is not one of greater")
        assertThat(CatalogFee("0.07", "0.01", "0.02").fee())
            .isEqualTo(ProbabilityFee(BigDecimal("0.07"), BigDecimal("0.01"), BigDecimal("0.02")))
        assertThat(CatalogFee("0.07", null, "0.02").fee().makerRate).isNull()
    }

    @Test
    fun `a listed event round trips with every optional set and none`() {
        roundTrips(bare)
        roundTrips(full)
        decodes(
            """{"code":"c","event":"e","predicate":{"type":"none"},"openAtMs":1,"closeAtMs":2,"expiryMs":3}""",
            ListedEvent("c", "e", CatalogStrike("none"), 1L, 2L, 3L),
        )
        val body = """{"code":"c","event":"e","predicate":{"type":"none"},"openAtMs":1,"closeAtMs":2,"expiryMs":3}"""
        refusesWithout<ListedEvent>(body, "code", "event", "predicate", "openAtMs", "closeAtMs", "expiryMs")
        refusesWrongType<ListedEvent>(
            body,
            "occursAtMs",
            "occasion",
            "canCloseEarly",
            "exclusive",
            "frequency",
            "title",
            "fee",
            "outcome",
            "predicate",
        )
    }

    @Test
    fun `a listed event maps to its symbol, outcome and terms`() {
        assertThat(bare.qktSymbol(root)).isEqualTo("KALSHI:KX_A")
        assertThat(bare.outcomeOrNull()).isNull()
        assertThat(full.outcomeOrNull()).isEqualByComparingTo("1")
        val terms = full.terms(root)
        assertThat(terms.occasion).isEqualTo("o")
        assertThat(terms.canCloseEarly).isTrue()
        assertThat(terms.fee?.takerRate).isEqualByComparingTo("0.07")
        assertThat(terms.outcome).isEqualByComparingTo("1")
        assertThat(bare.terms(root).fee).isNull()
        assertThat(bare.terms(root).outcome).isNull()
    }

    @Test
    fun `a listed event that is not valid terms names its code`() {
        assertThatThrownBy { bare.copy(expiryMs = 1L).terms(root) }
            .hasMessageContaining("event contract 'KX-A' of $root: expiryMs must be >= closeAtMs")
        assertThatThrownBy { bare.copy(predicate = CatalogStrike("between", "1")).terms(root) }
            .hasMessageContaining("event contract 'KX-A'")
        assertThatThrownBy { bare.copy(outcome = "9").terms(root) }
            .hasMessageContaining("outcome must be in")
    }

    @Test
    fun `a catalog round trips, sorts by expiry and refuses duplicates`() {
        val later = bare.copy(code = "KX-C", expiryMs = 9L, closeAtMs = 9L)
        val catalog = EventCatalog(root, listOf(later, full, bare))
        roundTrips(catalog)
        decodes("""{"root":"$root","contracts":[]}""", EventCatalog(root, emptyList()))
        refusesWithout<EventCatalog>("""{"root":"$root","contracts":[]}""", "root", "contracts")
        assertThat(catalog.sorted().contracts.map { it.code }).containsExactly("KX-A", "KX-B", "KX-C")
        assertThatThrownBy { EventCatalog(root, listOf(bare, bare)) }
            .hasMessageContaining("duplicate contract 'KX-A'")
        assertThatThrownBy { EventCatalog(root, listOf(bare.copy(code = "KX/A"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `merging adds new codes, keeps a recorded outcome and refuses a contradiction`() {
        val settled = bare.copy(outcome = "1")
        val catalog = EventCatalog(root, listOf(settled))
        val merged = catalog.merge(listOf(bare.copy(title = "new"), full))
        assertThat(merged.contracts.map { it.code }).containsExactly("KX-A", "KX-B")
        assertThat(merged.contracts[0].outcome).isEqualTo("1")
        assertThat(merged.contracts[0].title).isEqualTo("new")
        assertThat(
            catalog
                .merge(listOf(bare.copy(outcome = "1.0")))
                .contracts
                .single()
                .outcome,
        ).isEqualTo("1.0")
        assertThat(
            EventCatalog(root, listOf(bare))
                .merge(listOf(settled))
                .contracts
                .single()
                .outcome,
        ).isEqualTo("1")
        assertThat(EventCatalog(root, emptyList()).merge(listOf(bare)).contracts).containsExactly(bare)
        assertThatThrownBy { catalog.merge(listOf(bare.copy(outcome = "0"))) }
            .hasMessageContaining("event contract 'KX-A' of $root already resolved at 1; the import says 0")
    }
}
