package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.ChainQuoteLookup
import com.qkt.derivatives.options.chain.OptionChainFixture
import com.qkt.derivatives.options.chain.OptionChainFixture.Companion.ms
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionChainMarketSourceTest {
    private fun range(
        from: String,
        to: String,
    ) = TimeRange(Instant.parse(from), Instant.parse(to))

    @Test
    fun `each stored snapshot of a contract is a tick at its mark with the tradeable sides`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(
            Triple("2026-10-01T22:00:00Z", "100", 0L),
            Triple("2026-10-01T23:00:00Z", "40", 7_200_000L),
            Triple("2026-10-02T00:00:00Z", "0", 0L),
            Triple("2026-10-02T01:00:00Z", "3", 0L),
        )
        val source = OptionChainMarketSource(dir, f.registry)

        val ticks = source.ticks(f.symbol, range("2026-10-01T00:00:00Z", "2026-10-03T00:00:00Z")).toList()

        assertThat(source.supports(f.symbol)).isTrue()
        assertThat(source.supports("DERIBIT:BTC_USDC_2OCT26_92000_P")).isFalse()
        assertThat(ticks.map { it.timestamp })
            .containsExactly(
                ms("2026-10-01T22:00:00Z"),
                ms("2026-10-01T23:00:00Z"),
                ms("2026-10-02T01:00:00Z"),
                f.expiryMs,
            )
        assertThat(ticks[0].symbol).isEqualTo(f.symbol)
        assertThat(ticks[0].price).isEqualByComparingTo("100")
        assertThat(ticks[0].bid).isEqualByComparingTo("95")
        assertThat(ticks[0].ask).isEqualByComparingTo("105")
        assertThat(listOf(ticks[1].bid, ticks[1].ask)).containsOnlyNulls()
        assertThat(ticks[2].bid).isNull()
        assertThat(ticks[2].ask).isEqualByComparingTo("10")
    }

    @Test
    fun `stored quotes stop at expiry where one settlement print at the intrinsic value ends the stream`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(
            Triple("2026-10-02T07:00:00Z", "100", 0L),
            Triple("2026-10-02T08:00:00Z", "100", 0L),
            Triple("2026-10-02T09:00:00Z", "100", 0L),
        )
        val source = OptionChainMarketSource(dir, f.registry)

        val ticks = source.ticks(f.symbol, range("2026-10-02T07:00:00Z", "2026-10-03T00:00:00Z")).toList()

        assertThat(ticks.map { it.timestamp }).containsExactly(ms("2026-10-02T07:00:00Z"), f.expiryMs)
        assertThat(ticks.last().price).isEqualByComparingTo("3000")
        assertThat(listOf(ticks.last().bid, ticks.last().ask)).containsOnlyNulls()
        assertThat(source.ticks(f.symbol, range("2026-10-02T06:00:00Z", "2026-10-02T07:00:00Z")).toList()).isEmpty()
        assertThat(
            source.ticks(f.symbol, range("2026-10-02T07:00:00Z", "2026-10-02T08:00:00Z")).map { it.timestamp }.toList(),
        ).containsExactly(ms("2026-10-02T07:00:00Z"))
    }

    @Test
    fun `an out-of-the-money print is zero and an unrecorded delivery price fails naming the refresh`(
        @TempDir dir: Path,
    ) {
        val otm = OptionChainFixture(dir.resolve("otm"), deliveryPrice = "90000")
        otm.store(Triple("2026-10-02T07:00:00Z", "100", 0L))
        val window = range("2026-10-02T00:00:00Z", "2026-10-03T00:00:00Z")
        assertThat(
            OptionChainMarketSource(otm.dataRoot, otm.registry).ticks(otm.symbol, window).last().price,
        ).isEqualByComparingTo("0")

        val unknown = OptionChainFixture(dir.resolve("unknown"), deliveryPrice = null)
        unknown.store(Triple("2026-10-02T07:00:00Z", "100", 0L))
        assertThatThrownBy {
            OptionChainMarketSource(unknown.dataRoot, unknown.registry).ticks(unknown.symbol, window).toList()
        }.hasMessageContaining("qkt fetch DERIBIT:BTC_USDC --catalog")
    }

    @Test
    fun `the venue reads the exact quote at an instant and nothing between snapshots`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(Triple("2026-10-01T23:00:00Z", "100", 0L), Triple("2026-10-02T00:00:00Z", "120", 60_000L))
        val lookup = ChainQuoteLookup(dir, f.registry)

        assertThat(lookup.quoteAt(f.symbol, ms("2026-10-01T23:00:00Z"))?.mark).isEqualTo(BigDecimal("100"))
        assertThat(lookup.quoteAt(f.symbol, ms("2026-10-02T00:00:00Z"))?.markAgeMs).isEqualTo(60_000)
        assertThat(lookup.quoteAt(f.symbol, ms("2026-10-01T23:30:00Z"))).isNull()
        assertThat(lookup.quoteAt("DERIBIT:BTC_USDC_2OCT26_92000_P", ms("2026-10-02T00:00:00Z"))).isNull()
    }
}
