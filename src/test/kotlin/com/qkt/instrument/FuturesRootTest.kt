package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FuturesRootTest {
    private val es =
        FuturesRoot(
            root = "CME:ES",
            currency = "USD",
            multiplier = BigDecimal("50"),
            tickSize = BigDecimal("0.25"),
            volumeStep = BigDecimal.ONE,
            volumeMin = BigDecimal.ONE,
            volumeMax = null,
            calendar = "cme_equity",
            exchangeFeePerContract = BigDecimal("1.29"),
            takerFeeRate = BigDecimal.ZERO,
            margin = MarginTerms(BigDecimal("14000"), BigDecimal("12700"), MarginBasis.PER_CONTRACT),
        )

    @Test
    fun `meta for a contract carries the root spec and the expiry`() {
        val meta = es.metaFor("CME:ESZ6", expiryMs = 1_797_000_000_000L)
        assertThat(meta.contractSize).isEqualByComparingTo("50")
        assertThat(meta.pointSize).isEqualByComparingTo("0.25")
        assertThat(meta.digits).isEqualTo(2)
        assertThat(meta.currency).isEqualTo("USD")
        val terms = meta.derivative as FutureTerms
        assertThat(terms.root).isEqualTo("CME:ES")
        assertThat(terms.expiryMs).isEqualTo(1_797_000_000_000L)
        assertThat(terms.exchangeFeePerContract).isEqualByComparingTo("1.29")
    }

    @Test
    fun `digits follow the tick size`() {
        assertThat(es.copy(tickSize = BigDecimal("0.1")).metaFor("CME:ESZ6", 1L).digits).isEqualTo(1)
        assertThat(es.copy(tickSize = BigDecimal("1")).metaFor("CME:ESZ6", 1L).digits).isEqualTo(0)
        assertThat(es.copy(tickSize = BigDecimal("10")).metaFor("CME:ESZ6", 1L).digits).isEqualTo(0)
    }

    @Test
    fun `venue and symbol split the root`() {
        assertThat(es.venue).isEqualTo("CME")
        assertThat(es.symbol).isEqualTo("ES")
    }
}
