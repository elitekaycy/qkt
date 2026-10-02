package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionListing
import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradeChainBuilderTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val call = "BTC_USDC-2OCT26-92000-C"
    private val put = "BTC_USDC-1OCT26-80000-P"
    private val catalog =
        OptionCatalog(
            "DERIBIT:BTC_USDC",
            listOf(
                OptionListing(call, "92000", "call", ms("2026-10-02T08:00:00Z")),
                OptionListing(put, "80000", "put", ms("2026-10-01T08:00:00Z")),
            ),
        )
    private val builder = TradeChainBuilder(catalog, maxMarkAgeMs = 3 * 3_600_000)

    private fun trade(
        id: String,
        at: String,
        contract: String,
        mark: String,
        seq: Long = 1,
    ) = OptionTrade(id, ms(at), seq, contract, BigDecimal(mark), BigDecimal("48.7"), BigDecimal("83551.57"))

    private val trades =
        listOf(
            trade("1", "2026-10-01T05:30:00Z", put, "120"),
            trade("2", "2026-10-01T06:00:00Z", call, "15"),
            trade("3", "2026-10-01T07:15:00Z", call, "17.5"),
            trade("4", "2026-10-01T09:00:00Z", call, "20"),
        )

    private fun build(input: List<OptionTrade>) =
        builder.build(input, ms("2026-10-01T05:00:00Z"), ms("2026-10-01T10:00:00Z"), 3_600_000)

    @Test
    fun `a contract is quoted from its first trade on with that trade's mark and age, never before`() {
        val snapshots = build(trades).snapshots

        assertThat(snapshots.map { Instant.ofEpochMilli(it.atMs).toString() })
            .containsExactly(
                "2026-10-01T06:00:00Z",
                "2026-10-01T07:00:00Z",
                "2026-10-01T08:00:00Z",
                "2026-10-01T09:00:00Z",
            )
        val six = snapshots.first().quotes.associateBy { it.contract }
        assertThat(six.getValue(put).mark).isEqualByComparingTo("120")
        assertThat(six.getValue(put).markAgeMs).isEqualTo(1_800_000)
        assertThat(six.getValue(call).mark).isEqualByComparingTo("15")
        assertThat(six.getValue(call).markAgeMs).isZero()
        val quote = six.getValue(call)
        assertThat(listOf(quote.bid, quote.ask, quote.rate)).containsOnlyNulls()
        assertThat(quote.underlying).isEqualByComparingTo("83551.57")
        assertThat(quote.index).isEqualByComparingTo("83551.57")
        assertThat(quote.markIv).isEqualByComparingTo("48.7")
        assertThat(quote.source).isEqualTo(QuoteSource.TRADE)
    }

    @Test
    fun `a later trade replaces the mark but a trade after the instant is never seen`() {
        val byHour = build(trades).snapshots.associate { Instant.ofEpochMilli(it.atMs).toString() to it.quotes }

        val seven = byHour.getValue("2026-10-01T07:00:00Z").single { it.contract == call }
        assertThat(seven.mark).isEqualByComparingTo("15")
        assertThat(seven.markAgeMs).isEqualTo(3_600_000)
        val eight = byHour.getValue("2026-10-01T08:00:00Z").single { it.contract == call }
        assertThat(eight.mark).isEqualByComparingTo("17.5")
        assertThat(eight.markAgeMs).isEqualTo(2_700_000)
        assertThat(byHour.getValue("2026-10-01T09:00:00Z").single().mark).isEqualByComparingTo("20")
    }

    @Test
    fun `an option leaves the chain at its expiry instant`() {
        val byHour = build(trades).snapshots.associate { Instant.ofEpochMilli(it.atMs).toString() to it.quotes }

        assertThat(byHour.getValue("2026-10-01T07:00:00Z").map { it.contract }).containsExactly(put, call)
        assertThat(byHour.getValue("2026-10-01T08:00:00Z").map { it.contract }).containsExactly(call)
    }

    @Test
    fun `shuffled and duplicated trades build the same chain as a clean feed`() {
        val messy = (trades.reversed() + trades.take(2)).shuffled(java.util.Random(7))

        assertThat(build(messy)).isEqualTo(build(trades))
    }

    @Test
    fun `of two trades in one millisecond the later sequence number is the mark`() {
        val at = "2026-10-01T06:30:00Z"
        val pair = listOf(trade("9", at, call, "19", seq = 8), trade("8", at, call, "18", seq = 7))

        val quote =
            build(pair)
                .snapshots
                .first()
                .quotes
                .single()

        assertThat(quote.mark).isEqualByComparingTo("19")
    }

    @Test
    fun `trades of contracts missing from the catalog are counted, not quoted`() {
        val stray = trade("5", "2026-10-01T06:10:00Z", "BTC_USDC-9OCT26-99000-C", "3")

        val chain = build(trades + stray)

        assertThat(chain.unknownContracts).containsExactly("BTC_USDC-9OCT26-99000-C")
        assertThat(
            chain.snapshots
                .flatMap { it.quotes }
                .map { it.contract }
                .toSet(),
        ).containsExactlyInAnyOrder(put, call)
    }

    @Test
    fun `a mark older than the maximum age drops out until the contract trades again`() {
        val byHour =
            builder
                .build(trades, ms("2026-10-01T05:00:00Z"), ms("2026-10-01T14:00:00Z"), 3_600_000)
                .snapshots
                .associate { Instant.ofEpochMilli(it.atMs).toString() to it.quotes }

        assertThat(byHour.getValue("2026-10-01T12:00:00Z").single().markAgeMs).isEqualTo(3 * 3_600_000)
        assertThat(byHour).doesNotContainKey("2026-10-01T13:00:00Z")
    }

    @Test
    fun `the chain at an instant does not depend on how early the feed starts`() {
        val feed =
            listOf(trade("1", "2026-10-01T05:00:00Z", call, "15"), trade("2", "2026-10-01T08:30:00Z", call, "16"))
        val window = ms("2026-10-01T09:00:00Z")
        val lookedBack = feed.filter { it.timestampMs >= window - 3 * 3_600_000 }

        val fromAll = builder.build(feed, window, window + 3_600_000, 3_600_000)
        val fromLookback = builder.build(lookedBack, window, window + 3_600_000, 3_600_000)

        assertThat(fromLookback).isEqualTo(fromAll)
        assertThat(
            fromAll.snapshots
                .single()
                .quotes
                .single()
                .mark,
        ).isEqualByComparingTo("16")
    }

    @Test
    fun `an empty or inverted window and a non-positive interval are refused`() {
        val at = ms("2026-10-01T05:00:00Z")

        assertThatThrownBy { builder.build(trades, at, at, 60_000) }.hasMessageContaining("window")
        assertThatThrownBy { builder.build(trades, at, at + 60_000, 0) }.hasMessageContaining("interval")
        assertThatThrownBy { TradeChainBuilder(catalog, 0) }.hasMessageContaining("maxMarkAgeMs")
    }
}
