package com.qkt.marketdata.store.deribit

import com.qkt.derivatives.options.chain.QuoteSource
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Rows keep the field set of a real 2026-10-01 `get_book_summary_by_currency` response. */
class DeribitBookSnapshotTest {
    private val server = MockWebServer().also { it.start() }
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
        )
    private val call = "BTC_USDC-2OCT26-92000-C"
    private val put = "BTC_USDC-25DEC26-76000-P"
    private val catalog =
        OptionCatalog(
            root.root,
            listOf(
                OptionListing(call, "92000", "call", 1790928000000),
                OptionListing(put, "76000", "put", 1798185600000),
            ),
        )

    @AfterEach
    fun teardown() = server.shutdown()

    private fun row(
        name: String,
        at: Long,
        bid: String = "null",
        ask: String = "15.0",
        iv: String = "48.7",
    ) = """{"high":null,"low":null,"last":null,"instrument_name":"$name","bid_price":$bid,"ask_price":$ask,""" +
        """"open_interest":0.0,"mark_price":0.20172332,"interest_rate":0.0,"creation_timestamp":$at,""" +
        """"estimated_delivery_price":83476.57,"price_change":null,"volume":0.0,"mark_iv":$iv,""" +
        """"underlying_price":83502.39,"underlying_index":"BTC_USDC-2OCT26","base_currency":"BTC",""" +
        """"quote_currency":"USDC","volume_usd":0.0,"volume_notional":0.0,"mid_price":null}"""

    private fun snapshotOf(vararg rows: String): DeribitBookSnapshot.Taken {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":[${rows.joinToString(",")}]}"""))
        val base = server.url("/api/v2").toString().trimEnd('/')
        return DeribitBookSnapshot(DeribitClient(base, base, sleep = {})).take(root, catalog)
    }

    @Test
    fun `the book becomes one snapshot at its newest row, each row aged from its own time`() {
        val taken =
            snapshotOf(
                row(call, 1_790_821_352_026),
                row(put, 1_790_821_352_075, bid = "4980.0", ask = "5065.0"),
                row("ETH_USDC-2OCT26-3000-C", 1_790_821_352_080),
            )

        val snapshot = taken.snapshot
        assertThat(snapshot.atMs).isEqualTo(1_790_821_352_075)
        assertThat(snapshot.quotes.map { it.contract }).containsExactly(put, call)
        val c = snapshot.quotes.single { it.contract == call }
        assertThat(c.bid).isNull()
        assertThat(c.ask).isEqualTo(BigDecimal("15.0"))
        assertThat(c.mark).isEqualTo(BigDecimal("0.20172332"))
        assertThat(c.markIv).isEqualTo(BigDecimal("48.7"))
        assertThat(c.underlying).isEqualTo(BigDecimal("83502.39"))
        assertThat(c.rate).isEqualTo(BigDecimal("0.0"))
        assertThat(c.markAgeMs).isEqualTo(49)
        assertThat(c.source).isEqualTo(QuoteSource.BOOK)
        assertThat(snapshot.quotes.single { it.contract == put }.bid).isEqualTo(BigDecimal("4980.0"))
        assertThat(taken.unknownContracts).isEmpty()
    }

    @Test
    fun `contracts missing from the catalog are counted, not quoted`() {
        val taken = snapshotOf(row(call, 1_000), row("BTC_USDC-3OCT26-90000-C", 1_000))

        assertThat(taken.unknownContracts).containsExactly("BTC_USDC-3OCT26-90000-C")
        assertThat(taken.snapshot.quotes.map { it.contract }).containsExactly(call)
    }

    @Test
    fun `a book with none of the root's catalogued contracts is refused`() {
        assertThatThrownBy { snapshotOf(row("ETH_USDC-2OCT26-3000-C", 1_000)) }.hasMessageContaining("DERIBIT:BTC_USDC")
    }

    @Test
    fun `a contract still listed at or after its expiry is left out`() {
        val expiry = 1_790_928_000_000
        val taken = snapshotOf(row(call, expiry), row(put, expiry))

        assertThat(taken.snapshot.quotes.map { it.contract }).containsExactly(put)
    }
}
