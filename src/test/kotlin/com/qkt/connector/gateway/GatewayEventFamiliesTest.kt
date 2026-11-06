package com.qkt.connector.gateway

import com.qkt.connector.gateway.EventWire.ROOT
import com.qkt.connector.gateway.EventWire.closeMs
import com.qkt.connector.gateway.EventWire.code
import com.qkt.connector.gateway.EventWire.contract
import com.qkt.connector.gateway.EventWire.symbol
import com.qkt.instrument.EventTerms
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class GatewayEventFamiliesTest {
    private val fake = FakeGateway(emptyList())
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 2_000, retryAttempts = 1)
    private val heard = GatewaySymbols("KALSHI:")
    private val families = GatewayEventFamilies("KALSHI:", { client.instrumentPages(it) }, heard::include, 3_600_000L)

    init {
        EventWire.list(fake, EventWire.ladder() + contract(3, 1).copy(series = "KXOTHER", event = "KXOTHER-H3"))
    }

    @AfterEach
    fun stop() {
        families.close()
        fake.shutdown()
    }

    @Test
    fun `a family is read from the gateway's event listing of its series when first asked for`() {
        val family = requireNotNull(families.events().family(ROOT))

        assertThat(family.currency).isEqualTo("USD")
        assertThat(family.tickSize).isEqualByComparingTo("0.01")
        assertThat(families.contracts(ROOT)).containsExactlyElementsOf(
            listOf(1, 2).flatMap { e -> (1..4).map { s -> symbol(e, s) } },
        )
        val terms = families.lookup(symbol(2, 3))?.derivative as EventTerms
        assertThat(terms.root).isEqualTo(ROOT)
        assertThat(terms.predicate.floor).isEqualByComparingTo("300")
        assertThat(families.lookup("$ROOT@front.p70")?.derivative).isNull()
        assertThat(families.lookup("$ROOT@front.p70")?.volumeStep).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(families.aliveAt(ROOT, closeMs(1)))
            .containsExactly(symbol(2, 1), symbol(2, 2), symbol(2, 3), symbol(2, 4))
        // The account's symbol map heard of every contract, so an order on one finds its code and tick.
        assertThat(heard.venue(symbol(1, 2))).isEqualTo(code(1, 2))
        assertThat(heard.tick(symbol(1, 2))).isEqualByComparingTo("0.01")
    }

    @Test
    fun `another venue's family, an unlisted series and a contract of no family read are not answered`() {
        assertThat(families.family("DERIBIT:KXS")).isNull()
        assertThat(families.family("KALSHI:NOPE")).isNull()
        assertThat(families.contracts("KALSHI:NOPE")).isEmpty()
        assertThat(families.lookup(symbol(1, 1))).isNull()
        assertThat(families.lookup("KALSHI:NOPE@front.atm")).isNull()
    }

    @Test
    fun `a contract whose listing qkt cannot read is skipped and the rest of the family stands`() {
        fake.instruments[code(1, 1)] = contract(1, 1).copy(strikeType = null)

        assertThat(families.contracts(ROOT)).hasSize(7).doesNotContain(symbol(1, 1))
    }

    @Test
    fun `a poll applies what changed since the last read and keeps the list while nothing did`() {
        val before = families.contracts(ROOT)
        val since = mutableListOf<Long>()
        fake.changes = { at ->
            since += at
            WireInstrumentPage(emptyList(), asOf = at + 1)
        }

        families.refresh()
        assertThat(families.contracts(ROOT)).isSameAs(before)
        fake.changes = { at ->
            since += at
            WireInstrumentPage(
                listOf(contract(3, 1), contract(1, 2, trading = "disabled")),
                listOf(WireRemoved(code(1, 1))),
                asOf = at + 1,
            )
        }
        families.refresh()

        assertThat(since).containsExactly(LISTING_AS_OF, LISTING_AS_OF + 1)
        assertThat(families.contracts(ROOT)).contains(symbol(3, 1)).doesNotContain(symbol(1, 1))
        assertThat(families.lookup(symbol(1, 1))).isNotNull
        assertThat(families.lookup(symbol(1, 2))?.tradeMode?.name).isEqualTo("DISABLED")
    }

    @Test
    fun `a poll the gateway cannot answer reads the family in full, and keeps the last snapshot when that fails too`() {
        families.contracts(ROOT)
        fake.changes = { error("unreachable") }
        fake.instruments[code(1, 4)] = contract(1, 4, trading = "close_only")
        fake.failing["/v1/instruments"] = 1

        families.refresh()
        assertThat(families.lookup(symbol(1, 4))?.tradeMode?.name).isEqualTo("CLOSE_ONLY")

        fake.instruments[code(1, 4)] = contract(1, 4)
        fake.failing["/v1/instruments"] = 2
        families.refresh()
        assertThat(families.lookup(symbol(1, 4))?.tradeMode?.name).isEqualTo("CLOSE_ONLY")
        assertThat(families.contracts(ROOT)).hasSize(8)
    }

    @Test
    fun `a filtered read follows the cursor through every page and reports the first page's time`() {
        fake.listingPage = 3

        val page = client.instrumentPages(listOf("kind" to "event", "series" to "KXS"))

        assertThat(page.instruments.map { it.code }).hasSize(8).startsWith(code(1, 1)).endsWith(code(2, 4))
        assertThat(page.asOf).isEqualTo(LISTING_AS_OF)
    }
}
