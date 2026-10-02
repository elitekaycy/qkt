package com.qkt.derivatives.options.chain

import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChainRecorderTest {
    private val root = "DERIBIT:BTC_USDC"
    private val call = "BTC_USDC-2OCT26-92000-C"
    private val put = "BTC_USDC-2OCT26-90000-P"
    private val expiry = 10_000L
    private val written = mutableListOf<ChainSnapshot>()
    private val recorder =
        ChainRecorder(root, 1_000, expiries = { mapOf(call to expiry, put to expiry) }) {
            written +=
                it
        }

    private fun quote(
        contract: String,
        atMs: Long,
        mark: String = "100",
    ) = ChainQuote(atMs, contract, null, null, BigDecimal(mark), null, BigDecimal("83000"), null, 0L, QuoteSource.BOOK)

    @Test
    fun `each boundary a later quote crosses writes one snapshot of every contract's latest quote, aged to it`() {
        recorder.record(quote(call, 100, "100"))
        recorder.record(quote(call, 400, "101"))
        recorder.record(quote(put, 1_000, "50"))
        assertThat(written).isEmpty()

        recorder.record(quote(call, 1_200, "102"))

        val snapshot = written.single()
        assertThat(snapshot.atMs).isEqualTo(1_000L)
        assertThat(snapshot.quotes.map { it.contract to it.mark.toPlainString() })
            .containsExactlyInAnyOrder(call to "101", put to "50")
        assertThat(snapshot.quotes.associate { it.contract to it.markAgeMs }).isEqualTo(mapOf(call to 600L, put to 0L))
        assertThat(snapshot.quotes.map { it.atMs }.toSet()).containsExactly(1_000L)
    }

    @Test
    fun `a gap of several boundaries writes one snapshot, at the first boundary passed`() {
        recorder.record(quote(call, 100))

        recorder.record(quote(call, 5_500))

        assertThat(written.map { it.atMs }).containsExactly(1_000L)
        recorder.record(quote(call, 6_100))
        assertThat(written.map { it.atMs }).containsExactly(1_000L, 6_000L)
    }

    @Test
    fun `uncatalogued and expired contracts are left out, and an older quote never replaces a newer one`() {
        val recorder =
            ChainRecorder(root, 1_000, expiries = { mapOf(call to 10_000L, put to 2_000L) }) { written += it }
        recorder.record(quote("BTC_USDC-9OCT26-99000-C", 100))
        recorder.record(quote(put, 200))
        recorder.record(quote(call, 2_500, "120"))
        recorder.record(quote(call, 2_400, "119"))

        recorder.record(quote(call, 3_100, "121"))

        assertThat(written.map { s -> s.atMs to s.quotes.map { it.contract } })
            .containsExactly(1_000L to listOf(put), 3_000L to listOf(call))
        assertThat(
            written
                .last()
                .quotes
                .single()
                .mark,
        ).isEqualByComparingTo("120")
    }

    @Test
    fun `a boundary with nothing left to quote writes nothing`() {
        recorder.record(quote(call, 9_500))

        recorder.record(quote(call, 10_200))

        assertThat(written).isEmpty()
    }
}
